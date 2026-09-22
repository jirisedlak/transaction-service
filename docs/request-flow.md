# Request flow

How a request travels through the service, illustrated with a real run of
[`scripts/sample-flow.sh`](../scripts/sample-flow.sh) (full output in
[`sample-flow-output.txt`](sample-flow-output.txt)). All identifiers below come from that run.

```bash
./mvnw spring-boot:run          # terminal 1
./scripts/sample-flow.sh        # terminal 2 – 17 requests, prints every response
```

## The layers

```
client ──HTTP──▶ CorrelationIdFilter ──▶ PayloadLoggingFilter ──▶ Controller ──▶ IdempotencyService ──▶ Service
                 (X-Correlation-Id,      (logs request/response   (validation)   (claim key, replay,      │
                  MDC, access log)        bodies at DEBUG)                        release on failure)     ▼
                                                                                              EventStore.append
                                                                                              (assigns sequence)
                                                                                                          │
                                                                                                          ▼
                                                                                              EventBus.publish (FIFO)
                                                                                                          │
                     transaction-event-consumer thread  ◀─────────────────────────────────────────────────┘
                                │
                                ▼
                     TransactionProjector.project(txId)
                     reads EventStore after read-model version, applies in sequence order
                                │
                                ▼
                     TransactionRepository (read model)  ◀── GET /transactions/{id}
```

Two things hold the design together:

- **The event stream is the source of truth.** Every write is an append with a sequence number
  assigned atomically per transaction. The read model is a fold over that stream and records the
  `version` (last applied sequence) it reflects.
- **The consumer never trusts the notification.** On any event it re-reads the stream after the
  read model's version and applies whatever is there, in order, under a per-transaction lock. That is
  what makes duplicate or early deliveries harmless.

## 1. `POST /transactions` – creating a transaction

Request (step 1 of the sample run):

```http
POST /transactions
Idempotency-Key: tx-1790065741
X-Correlation-Id: sample-1790065741-01
Content-Type: application/json

{"accountId":"acc-1","amount":100.50,"currency":"EUR","reference":"order-42"}
```

What happens, in order (one log line per hop; note the thread column):

```
[http-nio-8080-exec-9      ] IdempotencyService   cid=sample-…-01 key=tx-1790065741 : First request in scope 'transactions', executing
[http-nio-8080-exec-9      ] InMemoryEventStore   cid=sample-…-01 key=tx-1790065741 : Appended CREATED as sequence 1 to stream of transaction 40e3dfd8-…
[http-nio-8080-exec-9      ] TransactionService   cid=sample-…-01 tx=40e3dfd8-… key=tx-1790065741 : Created transaction for account acc-1 (100.50 EUR), waiting for projection
[http-nio-8080-exec-9      ] InMemoryEventBus     cid=sample-…-01 tx=40e3dfd8-… key=tx-1790065741 : Published CREATED (seq 1) of transaction 40e3dfd8-…
[transaction-event-consumer] InMemoryEventBus     cid=sample-…-01 tx=40e3dfd8-… seq=1 key=tx-1790065741 : Consuming CREATED (seq 1)
[transaction-event-consumer] TransactionProjector cid=sample-…-01 tx=40e3dfd8-… seq=1 key=tx-1790065741 : Applied 1 event(s), version 0 -> 1, status NEW
[http-nio-8080-exec-9      ] http.payload         cid=sample-…-01 : > POST /transactions content-type=application/json idempotency-key=tx-1790065741 body={"accountId":"acc-1",…}
[http-nio-8080-exec-9      ] http.payload         cid=sample-…-01 : < 201 content-type=application/json body={"id":"40e3dfd8-…","status":"NEW",…,"version":1}
[http-nio-8080-exec-9      ] http.access          cid=sample-…-01 : POST /transactions -> 201 (52 ms)
```

1. `CorrelationIdFilter` takes `X-Correlation-Id` (or generates one) and puts it in the MDC.
2. `IdempotencyService` claims the key `tx-1790065741` in scope `transactions` and fingerprints the body.
3. `TransactionService` appends a `CREATED` event (sequence 1) carrying the request data. The
   transaction id is minted here.
4. The event is published. The request thread **waits** for the consumer so the `201` body can show
   the projected transaction.
5. On the consumer thread the projector applies the event: version 0 → 1, status `NEW`. The
   correlation id and idempotency key are still in the MDC because the bus carried them over.
6. The idempotency record is completed with the `201` response; the response goes out with
   `Location: /transactions/40e3dfd8-…` and the same `X-Correlation-Id`.

Response:

```json
HTTP 201  Location: /transactions/40e3dfd8-2aad-4161-b12f-dff43506ca00
{"id":"40e3dfd8-…","accountId":"acc-1","amount":100.5,"currency":"EUR","reference":"order-42",
 "status":"NEW","createdAt":"2026-09-22T08:29:01.476088Z","updatedAt":"…","version":1}
```

Repeating the exact request (step 2) returns the same `201` body with `Idempotency-Replayed: true`
and creates nothing; the same key with a different body (step 3) is a `422`; no key (step 4) is a
`400`. Every error body carries the `correlationId`.

## 2. `POST /events` – posting a lifecycle event

Request (step 5):

```http
POST /events
Idempotency-Key: ev-approve-1790065741
X-Correlation-Id: sample-1790065741-05
Content-Type: application/json

{"transactionId":"40e3dfd8-…","type":"APPROVED","payload":{"approver":"risk-engine"}}
```

```
[http-nio-8080-exec-6      ] IdempotencyService      cid=sample-…-05 key=ev-approve-… : First request in scope 'events', executing
[http-nio-8080-exec-6      ] InMemoryEventStore      cid=sample-…-05 tx=40e3dfd8-… key=ev-approve-… : Appended APPROVED as sequence 2 to stream of transaction 40e3dfd8-…
[http-nio-8080-exec-6      ] TransactionEventService cid=sample-…-05 tx=40e3dfd8-… key=ev-approve-… : Accepted APPROVED event as sequence 2
[http-nio-8080-exec-6      ] InMemoryEventBus        cid=sample-…-05 tx=40e3dfd8-… key=ev-approve-… : Published APPROVED (seq 2) of transaction 40e3dfd8-…
[transaction-event-consumer] InMemoryEventBus        cid=sample-…-05 tx=40e3dfd8-… seq=2 key=ev-approve-… : Consuming APPROVED (seq 2)
[transaction-event-consumer] TransactionProjector    cid=sample-…-05 tx=40e3dfd8-… seq=2 key=ev-approve-… : Applied 1 event(s), version 1 -> 2, status APPROVED
[http-nio-8080-exec-6      ] http.payload            cid=sample-…-05 : > POST /events … body={"transactionId":"40e3dfd8-…","type":"APPROVED",…}
[http-nio-8080-exec-6      ] http.payload            cid=sample-…-05 : < 202 content-type=application/json body={"id":"0b770b35-…","sequence":2,"type":"APPROVED",…}
[http-nio-8080-exec-6      ] http.access             cid=sample-…-05 : POST /events -> 202 (14 ms)
```

The difference from creation: the request thread does **not** wait for the consumer. The store
append (which also verifies the transaction exists – otherwise `404`) is the durable part; the
response is `202 Accepted` with the stored event, including its `sequence` and the
`correlationId` of this request. The read model catches up a moment later on the consumer thread.

Response:

```json
HTTP 202  Location: /transactions/40e3dfd8-…/events
{"id":"0b770b35-…","transactionId":"40e3dfd8-…","sequence":2,"type":"APPROVED",
 "payload":{"approver":"risk-engine"},"occurredAt":"…","recordedAt":"…","correlationId":"sample-1790065741-05"}
```

Step 6 repeats the request with the same key: `202` again, `Idempotency-Replayed: true`, and the
stream still has exactly one `APPROVED` (the consumer is never even notified). Step 7 posts
`SUBMITTED` (sequence 3).

## 3. Reading back: read model, stream, replay

- **Step 8** `GET /transactions/{id}` → `status: SUBMITTED, version: 3`. The read model has applied
  sequences 1–3.
- **Step 9** `GET /transactions/{id}/events` → the stream in receive order. Each event shows the
  correlation id of the request that produced it (`…-01`, `…-05`, `…-07`), which is how you get from
  "why is this transaction SUBMITTED?" to the exact request and its log lines.
- **Step 10** `POST /transactions/{id}/replay` → drops the read model and rebuilds it from the
  stream. Result is identical to step 8, which is the event-sourcing guarantee: state is a pure
  function of the stream.

## 4. Failure cases (steps 11–12)

| Request | Result | Idempotency key |
|---|---|---|
| event on unknown transaction | `404`, nothing appended | released – retry with the same key runs again |
| `type: CREATED` from a client | `400` with `errors.postableType` | never claimed (validation runs first) |

## 5. Reconciliation (steps 13–17)

A second transaction receives `RESERVED` twice under different keys (steps 14–15). Both are
accepted and appended – the API records facts, it does not police the lifecycle.

- **Step 16**, report now: only `duplicateEvents` (`RESERVED × 2`). Nothing is stale or missing yet
  because the transaction is younger than the 2-minute window.
- **Step 17**, report with `?asOf=` three minutes ahead: the transaction is **stale** (status
  `RESERVED` is not terminal), and two **missing transitions** are reported: `CREATED` without
  `APPROVED` and `RESERVED` without `SETTLED`. The first, healthy transaction is not reported at
  all – `SUBMITTED` is terminal.

## Following one request through the logs

Every log line carries `cid=` (correlation id), and where known `tx=`, `seq=` and `key=`. To see
everything one request did, including the asynchronous consumer work:

```bash
grep 'cid=sample-1790065741-05' server.log
```

To see everything that ever happened to one transaction:

```bash
grep 'tx=40e3dfd8-2aad-4161-b12f-dff43506ca00' server.log
```

Set `logging.level.http.payload` to `INFO` to drop the body dumps, or `http.payload-logging.enabled=false`
to stop buffering bodies altogether.
