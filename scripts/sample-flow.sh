#!/usr/bin/env bash
# Walks through the full request flow against a running transaction-service.
#
#   ./scripts/sample-flow.sh                # against http://localhost:8080
#   BASE_URL=http://host:port ./scripts/sample-flow.sh
#
# Every call sends an X-Correlation-Id so the log lines can be matched to the step.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RUN="$(date +%s)"                        # makes idempotency keys unique per run
STEP=0

# call <label> <method> <path> [json-body] [extra curl args...]
call() {
  local label="$1" method="$2" path="$3" body="${4:-}"; shift 3; [ $# -gt 0 ] && shift
  STEP=$((STEP + 1))
  local cid; cid="$(printf 'sample-%s-%02d' "$RUN" "$STEP")"
  echo
  echo "### $STEP. $label"
  echo "--> $method $path   (X-Correlation-Id: $cid)"
  [ -n "$body" ] && echo "    $body"
  local args=(-s -S -D /tmp/sample-headers.$$ -X "$method" "$BASE_URL$path" -H "X-Correlation-Id: $cid" "$@")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local response; response="$(curl "${args[@]}")"
  local status; status="$(head -1 /tmp/sample-headers.$$ | awk '{print $2}')"
  echo "<-- HTTP $status"
  grep -iE '^(Location|Idempotency-Replayed):' /tmp/sample-headers.$$ | sed 's/^/    /' | tr -d '\r' || true
  [ -n "$response" ] && echo "$response" | python3 -m json.tool | sed 's/^/    /'
  LAST_BODY="$response"
  rm -f /tmp/sample-headers.$$
}

json_field() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

echo "transaction-service sample flow against $BASE_URL (run $RUN)"

call "Create a transaction (starts in NEW)" POST /transactions \
  '{"accountId":"acc-1","amount":100.50,"currency":"EUR","reference":"order-42"}' \
  -H "Idempotency-Key: tx-$RUN"
TX="$(echo "$LAST_BODY" | json_field "['id']")"

call "Same key + same body: replayed, no second transaction" POST /transactions \
  '{"accountId":"acc-1","amount":100.50,"currency":"EUR","reference":"order-42"}' \
  -H "Idempotency-Key: tx-$RUN"

call "Same key + different body: rejected" POST /transactions \
  '{"accountId":"acc-1","amount":999,"currency":"EUR"}' \
  -H "Idempotency-Key: tx-$RUN"

call "Missing Idempotency-Key: rejected" POST /transactions \
  '{"accountId":"acc-1","amount":1,"currency":"EUR"}'

call "Approve (202: appended + emitted, consumer applies it)" POST /events \
  "{\"transactionId\":\"$TX\",\"type\":\"APPROVED\",\"payload\":{\"approver\":\"risk-engine\"}}" \
  -H "Idempotency-Key: ev-approve-$RUN"

call "Approve again with the same key: replayed, not recorded twice" POST /events \
  "{\"transactionId\":\"$TX\",\"type\":\"APPROVED\",\"payload\":{\"approver\":\"risk-engine\"}}" \
  -H "Idempotency-Key: ev-approve-$RUN"

call "Submit (expected after approval)" POST /events \
  "{\"transactionId\":\"$TX\",\"type\":\"SUBMITTED\"}" \
  -H "Idempotency-Key: ev-submit-$RUN"

call "Read model caught up: SUBMITTED, version 3" GET "/transactions/$TX"

call "Event stream in receive order, with the correlation id of each request" GET "/transactions/$TX/events"

call "Rebuild the read model by replaying the stream" POST "/transactions/$TX/replay"

call "Event on an unknown transaction: 404, key is not consumed" POST /events \
  '{"transactionId":"00000000-0000-0000-0000-000000000000","type":"APPROVED"}' \
  -H "Idempotency-Key: ev-unknown-$RUN"

call "Clients cannot post CREATED: 400" POST /events \
  "{\"transactionId\":\"$TX\",\"type\":\"CREATED\"}" \
  -H "Idempotency-Key: ev-created-$RUN"

# A second, "problematic" transaction for the reconciliation report
call "Create a second transaction" POST /transactions \
  '{"accountId":"acc-2","amount":5,"currency":"USD"}' \
  -H "Idempotency-Key: tx2-$RUN"
TX2="$(echo "$LAST_BODY" | json_field "['id']")"

call "RESERVED twice with different keys: accepted as facts (duplicate)" POST /events \
  "{\"transactionId\":\"$TX2\",\"type\":\"RESERVED\"}" -H "Idempotency-Key: ev2-a-$RUN"
call "..." POST /events \
  "{\"transactionId\":\"$TX2\",\"type\":\"RESERVED\"}" -H "Idempotency-Key: ev2-b-$RUN"

call "Reconciliation now: only the duplicate is reported" GET /reconciliation/report

LATER="$(python3 -c 'import datetime; print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(minutes=3)).strftime("%Y-%m-%dT%H:%M:%SZ"))')"
call "Reconciliation as if 3 minutes had passed: stale + missing transitions" GET "/reconciliation/report?asOf=$LATER"

echo
echo "Done. Transactions: $TX (healthy), $TX2 (duplicate RESERVED, never approved)."
echo "grep the server log for cid=sample-$RUN- to follow each step through the layers."
