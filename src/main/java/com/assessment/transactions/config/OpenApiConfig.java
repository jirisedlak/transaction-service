package com.assessment.transactions.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level OpenAPI metadata. Operation and schema details come from annotations on the
 * controllers and DTOs; the spec itself is generated from the running app (see pom.xml,
 * {@code springdoc-openapi-maven-plugin}) into {@code docs/openapi.yaml}.
 */
@Configuration
public class OpenApiConfig {

    public static final String TAG_TRANSACTIONS = "transactions";
    public static final String TAG_EVENTS = "events";
    public static final String TAG_RECONCILIATION = "reconciliation";

    @Bean
    public OpenAPI transactionServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("transaction-service")
                        .version("0.1.0")
                        .description("""
                                Event-sourced transaction service with idempotent write endpoints.

                                ## Idempotency
                                Both `POST` write endpoints require an `Idempotency-Key` header (1–255 characters, \
                                client generated, e.g. a UUID). Keys are scoped per endpoint.

                                | Situation | Result |
                                |---|---|
                                | first request with a key | operation runs (`201` / `202`) |
                                | same key, same body | original response replayed, `Idempotency-Replayed: true` |
                                | same key, different body | `422 Unprocessable Entity` |
                                | same key while the first request is still running | `409 Conflict` |
                                | missing / blank / too-long key | `400 Bad Request` |
                                | operation fails (e.g. `404`) | key released, the client may retry |

                                "Same body" is a SHA-256 fingerprint of the canonical JSON of the request.

                                ## Event sourcing
                                A transaction is an append-only stream of events. `POST /transactions` appends an \
                                internal `CREATED` event; `POST /events` appends a lifecycle event. Each append is \
                                assigned the next `sequence` number in the stream, which fixes the order the events \
                                were received. An internal consumer folds the stream into the transaction read model \
                                asynchronously and in order.

                                ## Lifecycle
                                `NEW → APPROVED → SUBMITTED` is the expected path; `RESERVED → SETTLED` and `REVERSED` \
                                are the other branches. `SUBMITTED`, `APPROVED`, `SETTLED` and `REVERSED` are terminal. \
                                The API records every event as a fact; deviations are surfaced by \
                                `GET /reconciliation/report`.

                                ## Tracing
                                Send `X-Correlation-Id` to tag a request; otherwise one is generated. It is echoed in \
                                the response header, stored on every event the request produces and included in \
                                error bodies as `correlationId`.
                                """))
                .tags(List.of(
                        new Tag().name(TAG_TRANSACTIONS).description("Create and read transactions, inspect and replay their event streams"),
                        new Tag().name(TAG_EVENTS).description("Post lifecycle events on a transaction"),
                        new Tag().name(TAG_RECONCILIATION).description("Detect stale, duplicated, incomplete and dead-lettered transactions")));
    }
}
