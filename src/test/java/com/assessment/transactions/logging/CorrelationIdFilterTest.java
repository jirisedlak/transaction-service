package com.assessment.transactions.logging;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CorrelationIdFilterTest {

    @Autowired
    MockMvc mvc;

    @Test
    void generatesCorrelationIdWhenClientSendsNone() throws Exception {
        mvc.perform(get("/transactions/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(header().string(TraceContext.CORRELATION_HEADER, matchesPattern("[0-9a-f]{16}")))
                .andExpect(jsonPath("$.correlationId").value(matchesPattern("[0-9a-f]{16}")));
    }

    @Test
    void echoesClientCorrelationIdInResponseHeaderProblemBodyAndStoredEvents() throws Exception {
        String cid = "client-trace-42";

        String tx = mvc.perform(post("/transactions")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header(TraceContext.CORRELATION_HEADER, cid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"acc-1","amount":10,"currency":"USD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(TraceContext.CORRELATION_HEADER, cid))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(tx, "$.id");

        mvc.perform(post("/events")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header(TraceContext.CORRELATION_HEADER, cid + "-event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"%s","type":"APPROVED"}
                                """.formatted(id)))
                .andExpect(status().isAccepted())
                .andExpect(header().string(TraceContext.CORRELATION_HEADER, cid + "-event"))
                .andExpect(jsonPath("$.correlationId").value(cid + "-event"));

        // the stream remembers which request produced each event
        mvc.perform(get("/transactions/" + id + "/events"))
                .andExpect(jsonPath("$[0].correlationId").value(cid))
                .andExpect(jsonPath("$[1].correlationId").value(cid + "-event"));

        // problem details carry the id too
        mvc.perform(post("/events")
                        .header(TraceContext.CORRELATION_HEADER, cid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").value(cid));
    }

    @Test
    void ignoresBlankOrOverlongClientCorrelationId() throws Exception {
        mvc.perform(get("/reconciliation/report").header(TraceContext.CORRELATION_HEADER, "   "))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceContext.CORRELATION_HEADER, matchesPattern("[0-9a-f]{16}")));
        mvc.perform(get("/reconciliation/report").header(TraceContext.CORRELATION_HEADER, "x".repeat(65)))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceContext.CORRELATION_HEADER, matchesPattern("[0-9a-f]{16}")));
    }
}
