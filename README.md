# transaction-service

Spring Boot 3.5 / Java 21 microservice exposing two idempotent REST endpoints:

| Method | Path           | Purpose                                   |
|--------|----------------|-------------------------------------------|
| POST   | `/transactions` | Create a new transaction                 |
| POST   | `/events`      | Post a lifecycle event on a transaction (`202 Accepted`, applied asynchronously) |
| GET    | `/transactions/{id}` | Read the transaction read model         |
| GET    | `/transactions/{id}/events` | The transaction's event stream, in receive order |
| POST   | `/transactions/{id}/replay` | Rebuild the read model by replaying the stream |
| GET    | `/reconciliation/report` | Detect stale, duplicated and incomplete transactions |

## Build & run

```bash
./mvnw clean verify          # compile + tests
./mvnw spring-boot:run       # start on http://localhost:8080
```

IntelliJ IDEA: **File → Open…** and pick `pom.xml` (or the folder); IDEA imports the Maven project.
`requests.http` contains ready-made requests for IDEA's HTTP Client.

## Idempotency contract

Both POST endpoints require an `Idempotency-Key` header (1–255 chars, client-generated, e.g. a UUID).
Keys are scoped per endpoint.

| Situation                                        | Result                                        |
|--------------------------------------------------|-----------------------------------------------|
| First request with a key                         | operation runs, `201 Created` / `202 Accepted`, `Location` |
| Same key + same body                             | original response replayed, `Idempotency-Replayed: true` |
| Same key + different body                        | `422 Unprocessable Entity`                    |
| Same key while the first request is still running| `409 Conflict`                                |
| Missing / blank / too-long key                   | `400 Bad Request`                             |
| Operation fails (e.g. `404` unknown transaction) | key is released; the client may retry         |

"Same body" is decided by a SHA-256 fingerprint of the canonical JSON of the request
(map key order does not matter).

Errors are returned as RFC 9457 `application/problem+json`; validation errors carry an
`errors` map of field → message.

## Architecture: event sourcing + internal consumer

```
POST /transactions ──▶ EventStore.append(CREATED) ──▶ EventBus ──▶ TransactionProjector ──▶ TransactionRepository
POST /events       ──▶ EventStore.append(<type>)  ──▶ (FIFO)  ──▶ (consumer)              (read model)
                        source of truth              single thread   folds stream → Transaction
```

- **Event store** (`eventsourcing.EventStore`): append-only stream per transaction, the system of
  record. Each append atomically assigns the next `sequence` number, so the order events were
  received is fixed in the stream itself. A stream always starts with an internal `CREATED` event
  carrying the request data; clients cannot post `CREATED`.
- **Event bus** (`eventsourcing.EventBus`): in-process, single consumer thread with a FIFO queue, so
  events are consumed one at a time in exactly the order they were published.
- **Consumer** (`eventsourcing.TransactionProjector`): on every event it reads the stream *after* the
  read model's current `version` and applies those events in sequence order, under the
  repository's per-transaction lock. That makes it **idempotent** (a duplicate notification finds
  nothing new; `Transaction.apply` ignores sequences it has already seen) and **order-safe** (an
  early notification just applies more; a gap in sequence is rejected).
- **Read model** (`domain.Transaction`, `TransactionRepository`): derived state only. `version` is
  the sequence of the last applied event. `POST /transactions/{id}/replay` throws the projection
  away and rebuilds it from the stream; `GET /transactions/{id}/events` shows the stream.

`POST /transactions` waits for the consumer to project the `CREATED` event so the `201` body shows
the transaction. `POST /events` returns `202 Accepted` with the stored event (including its
`sequence`) and does **not** wait: `GET /transactions/{id}` catches up a moment later.

## Transaction lifecycle

Every POSTed transaction starts in **`NEW`**. Each event moves it to the status of the same name.

```
NEW --APPROVED--> APPROVED --SUBMITTED--> SUBMITTED      (expected path)
                  RESERVED --SETTLED--> SETTLED           (settlement branch)
                  REVERSED                                (reversal)
```

Terminal statuses: `SUBMITTED`, `APPROVED`, `SETTLED`, `REVERSED`. `NEW` and `RESERVED` are not.
The `/events` endpoint records every event as a fact and does **not** reject out-of-order or
repeated events; deviations are surfaced by the reconciliation report.

## Reconciliation report

`GET /reconciliation/report` scans all transactions and returns:

| Section              | Finding                                                                                   |
|----------------------|-------------------------------------------------------------------------------------------|
| `staleTransactions`  | Not in a terminal state after `reconciliation.stale-after` (2 min)                        |
| `duplicateEvents`    | The same event type recorded more than once on a transaction                              |
| `missingTransitions` | An event without its expected successor: `CREATED` without `APPROVED`, `APPROVED` without `SUBMITTED`, `RESERVED` without `SETTLED` — unless the transaction was `REVERSED` |

Stale and missing-transition checks only look at transactions older than the 2-minute window, so
in-flight transactions are not reported; duplicates are reported regardless of age.

Time is simulated rather than waited for: pass `?asOf=<ISO-8601 instant>` to evaluate the report
as if that were the current time, e.g.

```bash
curl "localhost:8080/reconciliation/report?asOf=$(date -u -v+3M +%Y-%m-%dT%H:%M:%SZ)"
```

The window is configurable via `reconciliation.stale-after` in `application.yml`.

```json
{
  "asOf": "2026-09-22T10:03:00Z",
  "staleAfter": "PT2M",
  "staleTransactions":  [ { "transactionId": "...", "status": "APPROVED", "createdAt": "...", "age": "PT3M" } ],
  "duplicateEvents":    [ { "transactionId": "...", "eventType": "APPROVED", "occurrences": 2 } ],
  "missingTransitions": [ { "transactionId": "...", "status": "APPROVED", "recorded": "APPROVED", "expectedNext": "RESERVED" } ]
}
```

## Request / response shapes

`POST /transactions`
```json
{ "accountId": "acc-1", "amount": 100.50, "currency": "EUR", "reference": "order-42" }
```
→ `201` `{ "id": "...", "accountId": "acc-1", "amount": 100.50, "currency": "EUR", "reference": "order-42", "status": "NEW", "createdAt": "...", "updatedAt": "...", "version": 1 }`

`POST /events`
```json
{ "transactionId": "<uuid>", "type": "AUTHORIZED", "payload": { "authCode": "A1B2" }, "occurredAt": "2026-09-22T10:00:00Z" }
```
`type` is one of `APPROVED`, `SUBMITTED`, `RESERVED`, `SETTLED`, `REVERSED`; `payload` and `occurredAt` are optional (`occurredAt` defaults to now).
→ `202` `{ "id": "...", "transactionId": "...", "sequence": 2, "type": "APPROVED", "payload": {...}, "occurredAt": "...", "recordedAt": "..." }`

## Layout

```
com.assessment.transactions
├── api            controllers, DTOs, problem-detail exception handler
├── service        TransactionService, TransactionEventService
├── domain         Transaction (read model, replay/apply), TransactionEvent, EventType, TransactionRepository
├── eventsourcing  EventStore (+ in-memory), EventBus (+ in-memory FIFO), TransactionProjector (consumer)
├── idempotency    IdempotencyService, IdempotencyStore (+ in-memory impl), RequestFingerprinter
├── reconciliation ReconciliationService/Controller/Report, stale-after property
└── config         Clock bean
```

Persistence is a set of `ConcurrentHashMap`s (`InMemoryEventStore`, `InMemoryTransactionRepository`, `InMemoryIdempotencyStore`)
that live for the lifetime of the JVM. They sit behind interfaces so a database / Redis
implementation can replace them without touching the API or idempotency logic.
