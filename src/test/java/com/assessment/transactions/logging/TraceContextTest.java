package com.assessment.transactions.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.TransactionEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceContextTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void scopesRestorePreviousValuesOnClose() {
        try (TraceContext.Scope outer = TraceContext.with(TraceContext.CORRELATION_ID, "outer")) {
            try (TraceContext.Scope inner = TraceContext.with(Map.of(
                    TraceContext.CORRELATION_ID, "inner", TraceContext.TRANSACTION_ID, "tx"))) {
                assertThat(TraceContext.correlationId()).isEqualTo("inner");
                assertThat(MDC.get(TraceContext.TRANSACTION_ID)).isEqualTo("tx");
            }
            assertThat(TraceContext.correlationId()).isEqualTo("outer");
            assertThat(MDC.get(TraceContext.TRANSACTION_ID)).isNull();
        }
        assertThat(TraceContext.correlationId()).isNull();
    }

    @Test
    void eventScopeExposesEventIdentifiers() {
        UUID txId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        TransactionEvent event = new TransactionEvent(eventId, txId, 7, EventType.APPROVED, Map.of(),
                Instant.EPOCH, Instant.EPOCH, "cid-1");

        try (TraceContext.Scope ignored = TraceContext.forEvent(event)) {
            assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    TraceContext.CORRELATION_ID, "cid-1",
                    TraceContext.TRANSACTION_ID, txId.toString(),
                    TraceContext.EVENT_ID, eventId.toString(),
                    TraceContext.SEQUENCE, "7"));
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void snapshotAndRestoreCarryContextAcrossThreads() throws Exception {
        MDC.put(TraceContext.CORRELATION_ID, "from-request-thread");
        Map<String, String> snapshot = TraceContext.snapshot();
        String[] seen = new String[1];

        Thread worker = new Thread(() -> {
            try (TraceContext.Scope ignored = TraceContext.restore(snapshot)) {
                seen[0] = TraceContext.correlationId();
            }
        });
        worker.start();
        worker.join();

        assertThat(seen[0]).isEqualTo("from-request-thread");
    }
}
