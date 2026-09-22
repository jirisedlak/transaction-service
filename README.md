# transaction-service

Spring Boot 3.5 / Java 21 microservice built around two idempotent write endpoints, `POST /transactions` and `POST /events`, plus read, replay, reconciliation and operations endpoints:

| Method | Path           | Purpose                                   |
|--------|----------------|-------------------------------------------|
| POST   | `/transactions` | Create a new transaction                 |
| POST   | `/events`      | Post a lifecycle event on a transaction (`202 Accepted`, applied asynchronously) |
| GET    | `/transactions/{id}` | Read the transaction read model         |
| GET    | `/transactions/{id}/events` | The transaction's event stream, in receive order |
| POST   | `/transactions/{id}/replay` | Rebuild the read model by replaying the stream |
| GET    | `/reconciliation/report` | Detect stale, duplicated, incomplete and dead-lettered transactions |
| GET    | `/dead-letters`, `/dead-letters/{eventId}` | Events the consumer could not process |
| GET    | `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus` | Health (incl. consumer), metrics |
| POST   | `/dead-letters/{eventId}/redeliver` | Push a parked event through the consumer again |

**Documentation**

- [`docs/openapi.yaml`](docs/openapi.yaml) – OpenAPI 3 specification, **generated from the code at
  build time** (see [API specification](#api-specification-openapi)). Live copy and Swagger UI on the
  running service.
- [Architecture decisions](#architecture-decisions) – why the service is built the way it is, and the
  [future design](#future-design-kafka-with-partitions-and-postgresql) for multi-instance operation
  (PostgreSQL + Kafka).
- [`docs/request-flow.md`](docs/request-flow.md) – how a request travels through the layers
  (filters → idempotency → event store → bus → consumer → read model), step by step, with the real
  responses and log lines of a sample run.
- [`docs/sample-flow-output.txt`](docs/sample-flow-output.txt) – full output of that run.
- [`requests.http`](requests.http) – ready-made requests for IntelliJ's HTTP Client.

## Build & run

```bash
./mvnw clean verify          # compile + tests + regenerate docs/openapi.yaml
./mvnw spring-boot:run       # start on http://localhost:8080
```

`verify` briefly starts the app on port 18080 to export the OpenAPI spec (skip with
`-DskipOpenApi=true`).

### Docker

```bash
docker compose up --build     # build the image and start on http://localhost:8080
docker compose down           # stop (state is in-memory and lost with the container)
```

The [`Dockerfile`](Dockerfile) is multi-stage: the build stage compiles with the Maven wrapper on
a JDK 21 image (dependency layer cached separately), the runtime stage is a JRE 21 image running
the Spring Boot layered jar as a non-root user with a health check on `/actuator/health`. Tests are
skipped in the image build by default (`--build-arg SKIP_TESTS=false` to run them). Any property in
`application.yml` can be overridden through environment variables in
[`docker-compose.yml`](docker-compose.yml), e.g. `RECONCILIATION_STALE_AFTER=5m`.

IntelliJ IDEA: **File → Open…** and pick `pom.xml` (or the folder); IDEA imports the Maven project.
`requests.http` contains ready-made requests for IDEA's HTTP Client.

## Try it

With the service running, `scripts/sample-flow.sh` walks through the whole API in 17 requests
(create, idempotent replay, key reuse, events, read model, stream, replay, failure cases,
reconciliation) and prints every response:

```bash
./scripts/sample-flow.sh
```

[`docs/request-flow.md`](docs/request-flow.md) explains what happens inside the service on each
call, using the real responses and log lines from one run
([`docs/sample-flow-output.txt`](docs/sample-flow-output.txt)).

## Idempotency contract

Both write endpoints, `POST /transactions` and `POST /events`, require an `Idempotency-Key` header
(1–255 chars, client-generated, e.g. a UUID). Replay and dead-letter redelivery are idempotent by nature and take no key.
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

For a hop-by-hop walkthrough of a real request through these components, see
[`docs/request-flow.md`](docs/request-flow.md).

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

### Failure handling on the asynchronous path

- **Create never fails after the append.** If the consumer does not confirm the projection within
  `transactions.projection-timeout` (5 s) – or fails outright – `POST /transactions` still answers
  `201` from the stored `CREATED` event, and the read model catches up later. Because the request
  succeeded, its idempotency record is completed: a client retry with the same key **replays** the
  response instead of creating a second stream.
- **Retries, then a dead-letter queue.** A delivery that throws is retried
  `events.consumer.max-attempts` times (3, `retry-backoff` 100 ms apart) and then parked in the
  `DeadLetterStore` with the error and attempt count; the consumer thread moves on, so one poison
  event does not block the queue. Parked events are visible at `GET /dead-letters` and in the
  reconciliation report's `deadLetteredEvents`, and can be pushed through the consumer again with
  `POST /dead-letters/{eventId}/redeliver` (`200` with the transaction if it applied, `409` if it
  failed again and was parked again).

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
| `deadLetteredEvents` | Events the consumer could not apply after all retries; the read model of that transaction lags its stream |

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
  "missingTransitions": [ { "transactionId": "...", "status": "APPROVED", "recorded": "APPROVED", "expectedNext": "SUBMITTED" } ],
  "deadLetteredEvents": [ { "transactionId": "...", "eventId": "...", "sequence": 2, "type": "APPROVED", "error": "...", "attempts": 3, "failedAt": "..." } ]
}
```

## Logging & tracing

Every request gets a **correlation id**: taken from the `X-Correlation-Id` request header if the
client sends one (max 64 chars), otherwise generated. It is

- echoed back in the `X-Correlation-Id` response header,
- included as `correlationId` in every `application/problem+json` error body,
- stored on every event the request produces (visible in `GET /transactions/{id}/events`),
- carried in the SLF4J MDC for every log line of the request **and** of the asynchronous consumer
  work it triggers (the bus snapshots the publisher's context and restores it on the consumer thread).

Log lines are formatted as

```
2026-09-22T10:00:00.000+02:00  INFO [transaction-event-consumer] c.a.t.e.TransactionProjector         cid=3f1c2a9b8d7e6f50 tx=867e0dc9-... seq=2 key=47aff7de-... : Applied 1 event(s), version 1 -> 2, status APPROVED
```

with `cid` (correlation id), `tx` (transaction id), `seq` (event sequence) and `key`
(idempotency key) filled from the MDC where known. The `http.access` logger writes one line per
request (`POST /events -> 202 (3 ms)`). Log levels are set in `application.yml`
(`com.assessment.transactions: DEBUG` by default). `logging.TraceContext` is the single place that
defines the MDC keys and the scoped helpers used across the layers.

**Payload logging.** The `http.payload` logger records request and response bodies at DEBUG:

```
DEBUG [http-nio-8080-exec-1] http.payload cid=demo-trace-001 ... : > POST /transactions content-type=application/json idempotency-key=tx-demo-0001 body={"accountId":"acc-1","amount":100.50,"currency":"EUR"}
DEBUG [http-nio-8080-exec-1] http.payload cid=demo-trace-001 ... : < 201 content-type=application/json body={"id":"...","status":"NEW",...}
```

Only text-like content (JSON, problem+json, `text/*`) is printed; other bodies are summarised as
`<N bytes of type>`. Bodies are truncated to `http.payload-logging.max-length` characters
(default 2048). Turn it off with `http.payload-logging.enabled=false` or by raising
`logging.level.http.payload` above DEBUG; when off, the filter does not buffer bodies at all.

## Operability

**Metrics** (Micrometer; `/actuator/metrics/{name}`, Prometheus scrape at `/actuator/prometheus`,
all tagged `application=transaction-service`):

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `transaction.events.appended` | counter | `type` | events written to streams |
| `idempotency.requests` | counter | `scope`, `outcome` = executed / replayed / key_reuse / in_progress / failed | idempotency decisions |
| `events.consumer.deliveries` | counter | `outcome` = success / retry / dead_letter | consumer deliveries |
| `events.projection` | timer | `outcome` | time to fold pending events into a read model |
| `events.projection.applied` | counter | | events applied to read models |
| `events.consumer.queue.depth` | gauge | | events published, not yet consumed |
| `events.dead_letters` | gauge | | parked events – **the one to alert on** |
| `events.consumer.lag.max` / `.lag.transactions` | gauge | | largest stream-vs-read-model gap, and how many transactions are behind |

Meter names live in `observability.ServiceMetrics` / `ConsumerGauges`.

**Health.** `/actuator/health` includes an `eventConsumer` component (`observability.EventConsumerHealthIndicator`):

| Status | When | HTTP |
|---|---|---|
| `UP` | consumer running, answers a ping within `health.consumer.ping-timeout` (2 s), no dead letters | 200 |
| `DEGRADED` | dead-lettered events exist, or the consumer did not answer the ping (stuck / deep queue) | 200 |
| `DOWN` | consumer executor is shut down | 503 |

`DEGRADED` deliberately maps to 200: the Docker health check keeps the instance alive (a restart
would not fix a poison event and would lose in-memory state) while operators and alerting see the
reason in the details, together with `queueDepth`, `deadLetters`, `lagMax` and `lagTransactions`.

**Structured logs.** With the `docker` profile (`SPRING_PROFILES_ACTIVE=docker`, set in the image
and in `docker-compose.yml`) every log line is one JSON object in Elastic Common Schema, using
Spring Boot's built-in structured logging (`application-docker.yml`). The MDC fields
(`correlationId`, `transactionId`, `eventId`, `sequence`, `idempotencyKey`) become top-level keys,
so a log aggregator can filter on them directly. Without the profile the human-readable pattern applies.

**Graceful shutdown.** `server.shutdown=graceful`: on `SIGTERM` the server stops accepting
requests, in-flight ones finish (up to `spring.lifecycle.timeout-per-shutdown-phase`, 20 s), then
the event bus stops accepting events and drains what is already queued (up to
`events.consumer.drain-timeout`, 10 s). Anything still queued after that is not lost – it is in the
event store and is projected on the next event for that transaction or on replay.
`docker-compose.yml` sets `stop_grace_period` accordingly.

## API specification (OpenAPI)

The spec is **generated from the code**, not written by hand, so it cannot drift from the
controllers and DTOs:

- [springdoc-openapi](https://springdoc.org) builds it at runtime from the Spring MVC mappings,
  Bean Validation constraints and the `@Operation` / `@Schema` annotations on the controllers and DTOs.
  Top-level description and tags live in `config.OpenApiConfig`.
- During `./mvnw verify` the `spring-boot-maven-plugin` starts the app (port 18080), the
  `springdoc-openapi-maven-plugin` fetches `/v3/api-docs.yaml` and writes
  [`docs/openapi.yaml`](docs/openapi.yaml), then the app is stopped. The file is committed so it can
  be read without building; regenerate it with a build. `-DskipOpenApi=true` skips this step.
- `OpenApiSpecTest` checks the live spec covers every endpoint and that its enums match the domain.

On a running service:

| URL | What |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Swagger UI – browse and try the endpoints |
| `http://localhost:8080/v3/api-docs` | spec as JSON |
| `http://localhost:8080/v3/api-docs.yaml` | spec as YAML (what the build exports) |

## Request / response shapes

`POST /transactions`
```json
{ "accountId": "acc-1", "amount": 100.50, "currency": "EUR", "reference": "order-42" }
```
→ `201` `{ "id": "...", "accountId": "acc-1", "amount": 100.50, "currency": "EUR", "reference": "order-42", "status": "NEW", "createdAt": "...", "updatedAt": "...", "version": 1 }`

`POST /events`
```json
{ "transactionId": "<uuid>", "type": "APPROVED", "payload": { "approver": "risk-engine" }, "occurredAt": "2026-09-22T10:00:00Z" }
```
`type` is one of `APPROVED`, `SUBMITTED`, `RESERVED`, `SETTLED`, `REVERSED`; `payload` and `occurredAt` are optional (`occurredAt` defaults to now).
→ `202` `{ "id": "...", "transactionId": "...", "sequence": 2, "type": "APPROVED", "payload": {...}, "occurredAt": "...", "recordedAt": "...", "correlationId": "..." }`

## Layout

```
com.assessment.transactions
├── TransactionServiceApplication   entry point (@SpringBootApplication, @ConfigurationPropertiesScan)
├── api            TransactionController, EventController, DeadLetterController, DTOs,
│                  IdempotentResponses, ApiExceptionHandler (problem details)
├── service        TransactionService (+ TransactionProperties), TransactionEventService, DeadLetterService
├── domain         Transaction (read model, replay/apply), TransactionEvent, EventType, TransactionStatus,
│                  TransactionRepository (+ in-memory impl), TransactionNotFoundException
├── eventsourcing  EventStore (+ in-memory), EventBus (+ in-memory FIFO with retries, ConsumerProperties),
│                  TransactionProjector (consumer), DeadLetter, DeadLetterStore (+ in-memory)
├── idempotency    IdempotencyService, IdempotencyStore (+ in-memory impl), RequestFingerprinter,
│                  IdempotencyRecord, IdempotentResult, IdempotencyException
├── reconciliation ReconciliationService/Controller/Report, ReconciliationProperties (stale-after)
├── logging        TraceContext (MDC keys + scopes), CorrelationIdFilter (X-Correlation-Id, access log),
│                  PayloadLoggingFilter (+ PayloadLoggingProperties)
├── observability  ServiceMetrics, ConsumerGauges, EventConsumerHealthIndicator (+ HealthProperties)
└── config         ClockConfig (Clock bean), OpenApiConfig (spec metadata)
```

Persistence is a set of `ConcurrentHashMap`s (`InMemoryEventStore`, `InMemoryTransactionRepository`, `InMemoryIdempotencyStore`, `InMemoryDeadLetterStore`)
that live for the lifetime of the JVM. They sit behind interfaces so a database / Redis
implementation can replace them without touching the API or idempotency logic.


## Architecture decisions

Short ADR-style log of the choices made and why. Each one is easy to revisit; the code keeps them
in one place where possible.

1. **Spring Boot 3.5 / Java 21 / Maven, plain Java.** Records for DTOs, domain objects and events;
   no Lombok, no MapStruct. Maven wrapper committed so the build needs only a JDK.

2. **In-memory maps as the persistence layer, behind interfaces.** `InMemoryEventStore`,
   `InMemoryTransactionRepository`, `InMemoryIdempotencyStore` and `InMemoryDeadLetterStore` are
   `ConcurrentHashMap`s that live for the lifetime of the JVM. Each sits behind a small interface
   (`EventStore`, `TransactionRepository`, `IdempotencyStore`, `DeadLetterStore`) so a database /
   Redis implementation can replace it without touching the API, idempotency or projection logic. Consequence: single instance only,
   nothing survives a restart.

3. **Idempotency via a required `Idempotency-Key` header, scoped per endpoint.** Chosen over
   deriving a key from the body because the client is the only party that knows whether two
   identical bodies are one intent or two. Semantics follow the IETF idempotency-key draft: same
   key + same body replays the stored response (`Idempotency-Replayed: true`), same key + different
   body is `422`, a concurrent duplicate while the first is in flight is `409`, and a failed
   operation releases the key so the client can retry. "Same body" is a SHA-256 fingerprint of the
   canonical JSON (map key order ignored). The whole mechanism is one class, `IdempotencyService`,
   wrapping the operation as a `Supplier`, so controllers stay trivial.

4. **Event sourcing: the per-transaction event stream is the system of record.** Creation is itself
   an event (`CREATED`, carrying the request data) so every stream is self-contained and a
   transaction can be rebuilt from nothing. `Transaction` is a pure fold over the stream and carries
   `version` = last applied sequence. `POST /transactions/{id}/replay` proves it by rebuilding the
   read model from scratch.

5. **Ordering is fixed in the data, not in the transport.** `EventStore.append` assigns a contiguous
   `sequence` per stream inside a `ConcurrentHashMap.compute`, i.e. under a per-transaction lock.
   Whatever the bus or threads do afterwards, the order events were received is recorded.

6. **Internal event bus: in-process, single consumer thread, FIFO.** Simplest thing that gives
   "consumed in the order received". `EventBus` is an interface; `InMemoryEventBus` is a
   single-thread executor. A message broker could replace it without changing producers or the consumer.

7. **The consumer never trusts the notification.** `TransactionProjector` reads the stream *after*
   the read model's `version` and applies everything in sequence order, under the repository's
   per-transaction lock. This makes the consumer idempotent (duplicate deliveries find nothing new;
   `Transaction.apply` ignores sequences ≤ `version`) and order-safe (an early delivery just applies
   more; a gap is rejected). It also means a lost notification is healed by the next one.

8. **`POST /transactions` is synchronous, `POST /events` is `202 Accepted`.** Creation waits for the
   consumer (bounded by `transactions.projection-timeout`, 5 s; on timeout it answers from the stored
   event, see 12a) so the `201` body shows the new transaction – clients expect read-your-write on
   create. Events return as soon as the append is durable; the read model catches
   up on the consumer thread. This is the honest contract for an asynchronous consumer, and the
   `202` response carries the event's `sequence` so the client can see where it landed.

9. **The API records facts; it does not enforce the lifecycle.** An earlier version rejected invalid
   transitions with `409`. It was removed on purpose: with strict enforcement, duplicate events and
   skipped steps could never exist, which would make the reconciliation report dead code. Now every
   lifecycle event is appended, the status follows the latest event, and deviations are *detected*
   (decision 11) rather than *prevented*. Enforcement could be reintroduced as a configurable
   policy in `TransactionEventService` without touching storage.

10. **Lifecycle rules live in two enum methods.** `TransactionStatus.isTerminal()` and
    `EventType.expectedNext()` are the only places that know the expected path
    (`NEW → APPROVED → SUBMITTED`, `RESERVED → SETTLED`, `REVERSED`) and the terminal set
    (`SUBMITTED`, `APPROVED`, `SETTLED`, `REVERSED`). The requirement "SUBMITTED, APPROVED and
    SETTLED are terminal; a transaction needs to be APPROVED first and then can be SUBMITTED" is
    applied literally, with `REVERSED` kept terminal because a reversed transaction should not be
    reported as stale forever. Changing the rules is a one-line edit per state.

11. **Reconciliation is a read-only scan with simulated time.** `GET /reconciliation/report` walks all
    transactions and their streams. Stale and missing-transition checks apply only after the
    `reconciliation.stale-after` window (default 2 min) so in-flight transactions are not noise;
    duplicates are reported regardless of age. Time comes from an injectable `Clock` bean and the
    endpoint accepts `?asOf=` so the 2-minute rule can be exercised without waiting.
    `CREATED` cannot be posted by clients (`400`), so the stream's first event is always trustworthy.

12. **Errors are RFC 9457 problem details, always with `correlationId`.** One `@RestControllerAdvice`
    maps domain and idempotency exceptions; framework errors come from
    `ResponseEntityExceptionHandler`. Validation failures add an `errors` map (field → message).
    Failures never leave a claimed idempotency key behind: the key is released when the operation
    throws, and validation runs before the key is claimed.

12a. **A request is "done" when its event is appended, not when it is projected.** `POST
    /transactions` waits for the projection only as a convenience; on timeout or consumer failure it
    answers from the `CREATED` event. This keeps the idempotency record consistent with what
    actually happened (the stream exists), so a retry replays rather than duplicates.

12b. **Consumer failures go to a dead-letter queue, not into the request path.** The bus retries a
    failing delivery a bounded number of times and then parks the event with its error; the queue
    keeps flowing. Dead letters are observable (`/dead-letters`, reconciliation report) and
    replayable through the same consumer path. This is the in-memory stand-in for a Kafka DLQ topic.

12c. **Observability is built in, with a non-fatal DEGRADED health state.** Micrometer meters for
    every stage (append, idempotency outcome, delivery outcome, projection time, queue depth,
    dead letters, consumer lag) and a consumer health component. Dead letters and a slow consumer
    degrade health without failing the container health check, because restarting cannot fix them
    and would lose in-memory state; only a stopped consumer is DOWN. Logs switch to ECS JSON under
    the `docker` profile via Spring Boot's structured logging (no extra dependency), and shutdown is
    graceful with a bounded drain of the consumer queue.

13. **Tracing by correlation id in the MDC, propagated to the consumer.** `X-Correlation-Id` is taken
    from the client or generated, echoed back, stored on every event, and put in the MDC.
    `InMemoryEventBus` snapshots the publisher's MDC and restores it on the consumer thread, then
    adds the event's ids, so asynchronous log lines still carry the originating request. MDC keys are
    defined once in `logging.TraceContext`. Plain MDC was chosen over Micrometer Tracing to avoid
    infrastructure; the log pattern can absorb trace/span ids later.

14. **Payload logging is a separate, switchable filter.** Bodies are logged at DEBUG on the
    `http.payload` logger, only for text-like content, truncated, and the filter skips itself
    entirely when disabled so nothing is buffered. No field masking – the current model holds no
    secrets; masking belongs in this filter if that changes.

15. **The OpenAPI spec is generated from the code at build time, not hand-written.** springdoc reads
    the mappings, validation constraints and annotations; the Maven build starts the app, exports
    `docs/openapi.yaml` and stops it. A test asserts the live spec covers every endpoint and matches
    the domain enums. Rationale: a hand-written spec drifts; a generated one cannot.

16. **Containerised with a multi-stage build.** The Dockerfile builds with the Maven wrapper on a
    JDK image and runs on a JRE image as a non-root user, using Spring Boot's layered jar so
    dependency layers are cached across code changes. Tests and OpenAPI export are skipped in the
    image build (they belong to `mvnw verify` / CI). `docker-compose.yml` exposes port 8080 and a
    health check on the actuator endpoint; configuration is overridden via environment variables.

17. **Tests at three levels.** Domain and infrastructure unit tests (fold/apply semantics, sequence
    assignment under contention, 500 concurrently published events applied exactly once in order,
    MDC propagation), `IdempotencyService` tests including the in-flight race, and `@SpringBootTest`
    + MockMvc tests for every endpoint and error path, using Awaitility where the consumer is
    asynchronous.

**Known limitations:** single instance (shared stores needed for more), no persistence across
restarts, idempotency records are never expired, no authentication, no retention or snapshotting of
event streams.

## Future design: Kafka with partitions and PostgreSQL

The in-process `EventBus` and `EventStore` exist to keep this service self-contained. The next
step for running more than one instance is to replace them with **Apache Kafka**, keeping the
same contracts:

```
POST /events ──▶ EventStore.append (DB, assigns sequence) ──▶ outbox ──▶ Kafka topic `transaction-events`
                                                                        key = transactionId  ⇒  partition
                                                                                  │
                        consumer group `transaction-projector` (N instances, one partition each)
                                                                                  │
                                                                                  ▼
                                                     TransactionProjector.project(txId) ──▶ read-model DB
```

- **Partition by `transactionId`.** Kafka guarantees order only within a partition. Keying every
  event by its transaction id puts all events of one transaction on the same partition, so the
  "consumed in the order received" guarantee survives horizontal scaling. Different transactions
  spread over partitions and are processed in parallel.
- **One consumer per partition, many instances.** A consumer group over the topic gives each
  instance a subset of partitions; adding instances rebalances. Each partition is still consumed
  by a single thread, exactly like today's single consumer, just N of them.
- **Kafka is the transport, the event store stays the system of record.** Sequence numbers are
  assigned on append (database row with a unique `(transactionId, sequence)`), and the message is
  published from a **transactional outbox** so a crash between "stored" and "published" cannot lose
  or duplicate an event. Alternatively Kafka itself can be the store with infinite retention and the
  partition offset as the sequence – but replaying one transaction then means scanning a
  partition, so a queryable store is preferable.
- **The projector does not change.** It already reads the stream after the read model's `version`
  and ignores what it has seen (decision 7). That is exactly what makes at-least-once delivery from
  Kafka safe: redelivery after a rebalance or a crash finds nothing new. Enable the idempotent
  producer and commit offsets after the projection is written.
- **Shared stores.** `TransactionRepository` (read model) and `IdempotencyStore` move to a shared
  database / Redis; `IdempotencyStore.claim` becomes an atomic insert (`INSERT … ON CONFLICT`) so the
  in-flight `409` still works across instances. `POST /transactions` keeps waiting for its own
  projection either by projecting `CREATED` inline (idempotent, so harmless) or by polling the read
  model briefly.
- **Reconciliation** becomes a scheduled job over the store rather than an on-request scan, or a
  Kafka Streams / ksqlDB job over the same topic; the report shape stays the same.
- **Schema.** Events serialized as JSON (or Avro/Protobuf with a schema registry) with
  `type`, `sequence`, `correlationId` as headers so consumers can filter and tracing continues
  across the broker (`X-Correlation-Id` → Kafka header → MDC on the consumer, as the bus does now).

### PostgreSQL as the persistence layer

The in-memory maps are what tie the service to a single instance. Moving the three stores to
**PostgreSQL** is what makes multiple instances possible; Kafka then distributes the consumer work
between them. Suggested schema, one table per store, all under a single ACID transaction where it
matters:

```sql
-- EventStore: append-only, the system of record
CREATE TABLE transaction_event (
  transaction_id  uuid        NOT NULL,
  sequence        bigint      NOT NULL,             -- assigned on append, contiguous per stream
  event_id        uuid        NOT NULL UNIQUE,
  type            text        NOT NULL,
  payload         jsonb       NOT NULL,
  occurred_at     timestamptz NOT NULL,
  recorded_at     timestamptz NOT NULL,
  correlation_id  text,
  PRIMARY KEY (transaction_id, sequence)            -- ordering + no duplicate sequence
);

-- TransactionRepository: the read model (projection)
CREATE TABLE transaction_read_model (
  id          uuid PRIMARY KEY,
  account_id  text NOT NULL, amount numeric(19,4) NOT NULL, currency char(3) NOT NULL, reference text,
  status      text NOT NULL,
  created_at  timestamptz NOT NULL, updated_at timestamptz NOT NULL,
  version     bigint NOT NULL                       -- last applied sequence
);

-- IdempotencyStore
CREATE TABLE idempotency_record (
  scope        text NOT NULL, key text NOT NULL,
  fingerprint  text NOT NULL,
  status       int,                                 -- NULL while in progress
  body         jsonb,
  created_at   timestamptz NOT NULL DEFAULT now(),  -- for expiry
  PRIMARY KEY (scope, key)
);

-- transactional outbox for Kafka
CREATE TABLE outbox (
  id bigserial PRIMARY KEY, transaction_id uuid NOT NULL, event_id uuid NOT NULL,
  payload jsonb NOT NULL, headers jsonb NOT NULL, published_at timestamptz
);
```

How each guarantee maps onto the database:

| Today (in-memory)                                       | With PostgreSQL                                                                                                                  |
|---------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| `EventStore.append` inside `ConcurrentHashMap.compute`  | `INSERT … sequence = (SELECT coalesce(max(sequence),0)+1 …)` in a transaction; the `(transaction_id, sequence)` primary key rejects a concurrent duplicate, the loser retries. Or `SELECT … FOR UPDATE` on the stream head. |
| bus `publish` after append                              | insert into `outbox` in the **same** transaction as the event; a relay (Debezium or a small poller) publishes to Kafka and marks `published_at`. No lost or phantom events. |
| `IdempotencyStore.claim` via `putIfAbsent`              | `INSERT … ON CONFLICT (scope, key) DO NOTHING RETURNING …`; zero rows returned ⇒ read the existing record ⇒ replay / `422` / `409` exactly as now. Works across instances. `created_at` allows expiring old keys. |
| `TransactionRepository.compute` under a per-id lock     | `SELECT … FOR UPDATE` on the read-model row (or `INSERT … ON CONFLICT DO UPDATE … WHERE version < excluded.version`), apply pending events, `UPDATE`. Optimistic check on `version` makes concurrent projectors safe. |
| `POST /transactions/{id}/replay`                        | `DELETE` the row and re-fold the stream in one transaction.                                                                       |
| reconciliation scan                                     | three SQL queries (`status NOT IN (terminal) AND created_at < now() - interval`, `GROUP BY transaction_id, type HAVING count(*) > 1`, anti-joins for missing successors) – cheaper than loading everything. |

Running **N instances** then looks like: every instance serves HTTP and writes to the same
PostgreSQL; the outbox relay publishes to Kafka; the instances form one consumer group and each
projects the partitions assigned to it into the shared read model. Because projection is
idempotent and versioned, a rebalance mid-batch is harmless. Migrations with Flyway, access via
Spring Data JDBC or JPA, Testcontainers for the tests – all behind the same four interfaces.

Because the transport and stores sit behind `EventBus`, `EventStore`, `TransactionRepository` and
`IdempotencyStore`, both steps – PostgreSQL for persistence, Kafka for distribution – are changes
of implementations, not of the API or the domain.

### Next steps (independent of the infrastructure move)

Hardening:

1. **Expire idempotency records.** They currently live forever, so memory grows with every request
   and a key can never be legitimately reused. Add a `createdAt` and a TTL (typically 24 h) with a
   scheduled sweep; the PostgreSQL schema above already carries the column.
2. **Lease in-flight idempotency claims.** If the process dies while a key is claimed, the record
   stays "in progress" and every retry gets `409` forever. Give claims a lease with a timeout after
   which a retry may take over.
3. **Optimistic concurrency on the read model.** The projector is safe today because of the
   per-key `compute` lock; the PostgreSQL variant needs an explicit `version` guard
   (`UPDATE … WHERE version = ?`). Adding it now makes the swap mechanical and is cheap to test.
4. **Smarter catch-up around dead letters.** After an event is dead-lettered, the next event for the
   same transaction makes the projector re-read from the last applied version, hit the poison event
   again and dead-letter it again – correct but noisy. Skip known dead-lettered sequences during
   catch-up, or mark the transaction "projection blocked" so the report can say so explicitly.
5. **Optional strict lifecycle mode.** The API records facts by design (decision 9). A configurable
   strict mode that rejects events after `SETTLED` / `REVERSED` (or any off-path transition) would
   let the same service run in a stricter deployment without a fork.

API and product:

6. **Pagination and filtering on reads.** A transaction's stream and the reconciliation report grow
   without bound. Add `limit` / `after` (by `sequence`) to `GET /transactions/{id}/events` and to the
   report, and let the report be filtered by account or status.
7. **Read-your-writes on events.** `POST /events` returns `202`, so a client that immediately reads
   the transaction may see the previous status. Either support `Prefer: wait=<seconds>` to hold the
   response until the projection reaches the event, or document polling until `version` in
   `GET /transactions/{id}` reaches the `sequence` returned by the `202` – the sequence is already
   in the body, so the second option is nearly free.
8. **Snapshots for long streams.** Replay and catch-up fold the whole stream. Storing a snapshot of
   the read model every N events and replaying from the last snapshot keeps replay time bounded as
   transactions accumulate events.

Delivery and quality:

9. **CI.** A GitHub Actions workflow running `./mvnw verify` on push, failing if the regenerated
   `docs/openapi.yaml` differs from the committed one (spec drift) or if the Docker image does not
   build.
10. **Security.** There is no authentication. At minimum an API key or an OAuth2 resource-server
    setup with per-endpoint roles (read vs. write vs. operations such as replay and dead-letter
    redelivery), and rate limiting on the write endpoints, before anything faces the internet.
11. **Contract and load tests.** A small k6 or Gatling script hitting create and events
    concurrently over real HTTP would validate the ordering and idempotency guarantees under
    network concurrency, which the unit tests only cover in-process; a contract test against
    `docs/openapi.yaml` would keep clients honest.
