package com.assessment.transactions.observability;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability // registers the Prometheus registry/endpoint, which tests disable by default
class ObservabilityTest {

    @Autowired
    MockMvc mvc;

    private String createTransaction(String key) throws Exception {
        String body = mvc.perform(post("/transactions")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"acc-obs","amount":1,"currency":"EUR"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void healthReportsConsumerComponentWithDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.eventConsumer.status").value("UP"))
                .andExpect(jsonPath("$.components.eventConsumer.details.queueDepth").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.components.eventConsumer.details.deadLetters").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.components.eventConsumer.details.lagMax").exists())
                .andExpect(jsonPath("$.components.eventConsumer.details.lagTransactions").exists());
    }

    @Test
    void metricsCountAppendsIdempotencyOutcomesAndDeliveries() throws Exception {
        String key = UUID.randomUUID().toString();
        String id = createTransaction(key);
        createTransaction(key); // replayed
        mvc.perform(post("/events")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"%s","type":"APPROVED"}
                                """.formatted(id)))
                .andExpect(status().isAccepted());
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get("/transactions/" + id)).andExpect(jsonPath("$.version").value(2)));

        mvc.perform(get("/actuator/metrics/transaction.events.appended").param("tag", "type:CREATED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(greaterThanOrEqualTo(1.0)));
        mvc.perform(get("/actuator/metrics/transaction.events.appended").param("tag", "type:APPROVED"))
                .andExpect(jsonPath("$.measurements[0].value").value(greaterThanOrEqualTo(1.0)));
        mvc.perform(get("/actuator/metrics/idempotency.requests").param("tag", "outcome:replayed"))
                .andExpect(jsonPath("$.measurements[0].value").value(greaterThanOrEqualTo(1.0)));
        mvc.perform(get("/actuator/metrics/events.consumer.deliveries").param("tag", "outcome:success"))
                .andExpect(jsonPath("$.measurements[0].value").value(greaterThanOrEqualTo(2.0)));
        mvc.perform(get("/actuator/metrics/events.projection"))
                .andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value").exists());
        mvc.perform(get("/actuator/metrics/events.consumer.lag.max"))
                .andExpect(jsonPath("$.measurements[0].value").value(0.0));
    }

    @Test
    void prometheusEndpointExposesServiceMeters() throws Exception {
        createTransaction(UUID.randomUUID().toString());
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("transaction_events_appended_total")))
                .andExpect(content().string(containsString("events_dead_letters")))
                .andExpect(content().string(containsString("events_consumer_queue_depth")))
                .andExpect(content().string(containsString("events_consumer_lag_max")))
                .andExpect(content().string(containsString("idempotency_requests_total")))
                .andExpect(content().string(containsString("application=\"transaction-service\"")));
    }
}
