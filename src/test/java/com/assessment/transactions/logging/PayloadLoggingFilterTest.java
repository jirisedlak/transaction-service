package com.assessment.transactions.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PayloadLoggingFilterTest {

    @Autowired
    MockMvc mvc;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger payloadLogger;

    @BeforeEach
    void captureLog() {
        payloadLogger = (Logger) LoggerFactory.getLogger(PayloadLoggingFilter.LOGGER_NAME);
        appender.start();
        payloadLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        payloadLogger.detachAppender(appender);
    }

    private List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void logsRequestAndResponseBodiesWithCorrelationIdAndStillReturnsBody() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(post("/transactions")
                        .header("Idempotency-Key", key)
                        .header(TraceContext.CORRELATION_HEADER, "payload-cid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"acc-9","amount":42,"currency":"CZK"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value("acc-9")); // body reached the client despite buffering

        assertThat(lines()).hasSize(2);
        assertThat(lines().get(0))
                .startsWith("> POST /transactions")
                .contains("content-type=application/json")
                .contains("idempotency-key=" + key)
                .contains("\"accountId\":\"acc-9\"");
        assertThat(lines().get(1))
                .startsWith("< 201")
                .contains("\"currency\":\"CZK\"")
                .contains("\"status\":\"NEW\"");
        assertThat(appender.list).allSatisfy(event ->
                assertThat(event.getMDCPropertyMap()).containsEntry(TraceContext.CORRELATION_ID, "payload-cid"));
    }

    @Test
    void logsEmptyBodiesAndProblemResponses() throws Exception {
        mvc.perform(get("/transactions/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());

        assertThat(lines().get(0)).contains("body=<empty>");
        assertThat(lines().get(1)).startsWith("< 404").contains("application/problem+json").contains("Transaction not found");
    }

    @Test
    void truncatesLongBodies() throws Exception {
        String longReference = "r".repeat(3000);
        mvc.perform(post("/transactions")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"acc-1","amount":1,"currency":"EUR","reference":"%s"}
                                """.formatted(longReference)))
                .andExpect(status().isBadRequest()); // reference > 255 chars

        String requestLine = lines().get(0);
        assertThat(requestLine).contains("...(").contains("bytes total)");
        assertThat(requestLine.length()).isLessThan(2048 + 200);
    }
}
