package com.assessment.transactions.api;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class EventControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    private String transactionId;

    @BeforeEach
    void createTransaction() throws Exception {
        MvcResult result = mvc.perform(post("/transactions")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"acc-1","amount":10,"currency":"USD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        transactionId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private static String eventBody(String txId, String type) {
        return """
                {"transactionId":"%s","type":"%s","payload":{"authCode":"A1B2","amount":10}}
                """.formatted(txId, type);
    }

    private ResultActions postEvent(String key, String txId, String type) throws Exception {
        return mvc.perform(post("/events")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(eventBody(txId, type)));
    }

    private ResultActions postEvent(String type) throws Exception {
        return postEvent(UUID.randomUUID().toString(), transactionId, type);
    }

    /** The consumer updates the read model asynchronously, so wait for it. */
    private void awaitStatus(String status, int version) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mvc.perform(get("/transactions/" + transactionId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value(status))
                        .andExpect(jsonPath("$.version").value(version)));
    }

    @Test
    void acceptsEventAndConsumerMovesTransactionToNewStatus() throws Exception {
        postEvent("APPROVED")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/transactions/" + transactionId + "/events"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.transactionId").value(transactionId))
                .andExpect(jsonPath("$.sequence").value(2))
                .andExpect(jsonPath("$.type").value("APPROVED"))
                .andExpect(jsonPath("$.payload.authCode").value("A1B2"))
                .andExpect(jsonPath("$.occurredAt").isNotEmpty())
                .andExpect(jsonPath("$.recordedAt").isNotEmpty());

        awaitStatus("APPROVED", 2);
    }

    @Test
    void walksExpectedPathAndStreamKeepsReceiveOrder() throws Exception {
        postEvent("APPROVED").andExpect(status().isAccepted()).andExpect(jsonPath("$.sequence").value(2));
        postEvent("SUBMITTED").andExpect(status().isAccepted()).andExpect(jsonPath("$.sequence").value(3));
        awaitStatus("SUBMITTED", 3);

        mvc.perform(get("/transactions/" + transactionId + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].sequence").value(1))
                .andExpect(jsonPath("$[0].type").value("CREATED"))
                .andExpect(jsonPath("$[0].payload.accountId").value("acc-1"))
                .andExpect(jsonPath("$[1].type").value("APPROVED"))
                .andExpect(jsonPath("$[2].type").value("SUBMITTED"));
    }

    @Test
    void outOfOrderAndDuplicateEventsAreRecordedAsFacts() throws Exception {
        // reconciliation, not the API, flags these
        postEvent("SETTLED").andExpect(status().isAccepted());
        postEvent("SETTLED").andExpect(status().isAccepted());
        postEvent("APPROVED").andExpect(status().isAccepted());

        awaitStatus("APPROVED", 4);
    }

    @Test
    void repeatingSameKeyAndPayloadReplaysSameEventWithoutRecordingTwice() throws Exception {
        String key = UUID.randomUUID().toString();

        MvcResult first = postEvent(key, transactionId, "APPROVED")
                .andExpect(status().isAccepted())
                .andReturn();

        MvcResult second = postEvent(key, transactionId, "APPROVED")
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();

        Assertions.assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        awaitStatus("APPROVED", 2);
        mvc.perform(get("/transactions/" + transactionId + "/events"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void reusingKeyWithDifferentPayloadIsRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        postEvent(key, transactionId, "APPROVED").andExpect(status().isAccepted());
        postEvent(key, transactionId, "SUBMITTED").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownTransactionIsNotFoundAndKeyCanBeRetried() throws Exception {
        String key = UUID.randomUUID().toString();

        postEvent(key, UUID.randomUUID().toString(), "APPROVED").andExpect(status().isNotFound());

        // failed attempts do not consume the key
        postEvent(key, transactionId, "APPROVED")
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));
    }

    @Test
    void createdCannotBePostedByClients() throws Exception {
        postEvent("CREATED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.postableType").exists());
    }

    @Test
    void unknownEventTypeIsBadRequest() throws Exception {
        postEvent("NEW").andExpect(status().isBadRequest());
        postEvent("FOO").andExpect(status().isBadRequest());
    }

    @Test
    void missingFieldsAreBadRequestWithFieldErrors() throws Exception {
        mvc.perform(post("/events")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.transactionId").exists())
                .andExpect(jsonPath("$.errors.type").exists());
    }
}
