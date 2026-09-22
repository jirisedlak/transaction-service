package com.assessment.transactions.api;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.eventsourcing.EventBus;
import com.assessment.transactions.eventsourcing.InMemoryEventBus;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The consumer is too slow to confirm the projection of CREATED within the configured timeout.
 * The request must still succeed from the stored event, and a retry with the same key must replay
 * that response rather than create a second stream.
 */
@SpringBootTest(properties = "transactions.projection-timeout=200ms")
@AutoConfigureMockMvc
class CreateProjectionTimeoutTest {

    @TestConfiguration
    static class SlowBus {
        /** Delegates to a real bus so the projector still runs, but never confirms completion. */
        @Bean
        @Primary
        EventBus neverConfirmingBus() {
            InMemoryEventBus real = new InMemoryEventBus();
            return new EventBus() {
                @Override
                public CompletableFuture<Void> publish(TransactionEvent event) {
                    real.publish(event);
                    return new CompletableFuture<>(); // never completes
                }

                @Override
                public void subscribe(Consumer<TransactionEvent> subscriber) {
                    real.subscribe(subscriber);
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    private static final String BODY = """
            {"accountId":"acc-timeout","amount":7,"currency":"EUR","reference":"slow"}
            """;

    @Test
    void createSucceedsFromEventAndRetryReplaysInsteadOfDuplicating() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = mvc.perform(post("/transactions")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.reference").value("slow"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(first, "$.id");

        // retry with the same key: replayed, same id, no new stream
        mvc.perform(post("/transactions")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));

        mvc.perform(get("/transactions/" + id + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // the read model catches up in the background
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get("/transactions/" + id))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.version").value(1)));
    }
}
