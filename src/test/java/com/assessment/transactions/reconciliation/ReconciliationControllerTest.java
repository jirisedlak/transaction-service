package com.assessment.transactions.reconciliation;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    private String createTransaction() throws Exception {
        String body = mvc.perform(post("/transactions")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"acc-1","amount":10,"currency":"USD"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private void postEvent(String txId, String type) throws Exception {
        mvc.perform(post("/events")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"%s","type":"%s"}
                                """.formatted(txId, type)))
                .andExpect(status().isAccepted());
    }

    @Test
    void reportReflectsSimulatedTimeViaAsOf() throws Exception {
        String tx = createTransaction();
        postEvent(tx, "RESERVED");
        postEvent(tx, "RESERVED");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get("/transactions/" + tx)).andExpect(jsonPath("$.status").value("RESERVED")));
        String byTx = "[?(@.transactionId == '%s')]".formatted(tx);

        // right now: nothing stale or missing yet, but the duplicate is visible
        mvc.perform(get("/reconciliation/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").isNotEmpty())
                .andExpect(jsonPath("$.staleAfter").value("PT2M"))
                .andExpect(jsonPath("$.staleTransactions" + byTx).isEmpty())
                .andExpect(jsonPath("$.missingTransitions" + byTx).isEmpty())
                .andExpect(jsonPath("$.duplicateEvents" + byTx + ".eventType").value("RESERVED"))
                .andExpect(jsonPath("$.duplicateEvents" + byTx + ".occurrences").value(2));

        // two simulated minutes later
        String later = Instant.now().plusSeconds(121).toString();
        mvc.perform(get("/reconciliation/report").param("asOf", later))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value(later))
                .andExpect(jsonPath("$.staleTransactions" + byTx + ".status").value("RESERVED"))
                .andExpect(jsonPath("$.missingTransitions" + byTx, hasSize(2)))
                .andExpect(jsonPath("$.missingTransitions" + byTx + "[?(@.recorded == 'CREATED')].expectedNext").value("APPROVED"))
                .andExpect(jsonPath("$.missingTransitions" + byTx + "[?(@.recorded == 'RESERVED')].expectedNext").value("SETTLED"));
    }

    @Test
    void malformedAsOfIsBadRequest() throws Exception {
        mvc.perform(get("/reconciliation/report").param("asOf", "yesterday"))
                .andExpect(status().isBadRequest());
    }
}
