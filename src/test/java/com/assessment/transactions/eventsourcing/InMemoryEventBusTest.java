package com.assessment.transactions.eventsourcing;

import static org.assertj.core.api.Assertions.assertThat;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.logging.TraceContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class InMemoryEventBusTest {

    private final InMemoryEventBus bus = new InMemoryEventBus();

    @AfterEach
    void tearDown() {
        bus.shutdown();
        MDC.clear();
    }

    private static TransactionEvent event(long seq, String correlationId) {
        return new TransactionEvent(UUID.randomUUID(), UUID.randomUUID(), seq, EventType.APPROVED, Map.of(),
                Instant.EPOCH, Instant.EPOCH, correlationId);
    }

    @Test
    void consumerSeesPublisherTracingContextAndEventIdentifiers() throws Exception {
        List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
        List<String> threads = new CopyOnWriteArrayList<>();
        bus.subscribe(e -> {
            seen.add(TraceContext.snapshot());
            threads.add(Thread.currentThread().getName());
        });

        TransactionEvent event = event(3, "cid-from-event");
        MDC.put(TraceContext.IDEMPOTENCY_KEY, "key-1");
        MDC.put(TraceContext.CORRELATION_ID, "cid-from-publisher");
        bus.publish(event).get(5, TimeUnit.SECONDS);

        assertThat(threads).containsExactly("transaction-event-consumer");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0))
                .containsEntry(TraceContext.IDEMPOTENCY_KEY, "key-1")          // publisher context propagated
                .containsEntry(TraceContext.CORRELATION_ID, "cid-from-event")  // event's own id wins
                .containsEntry(TraceContext.SEQUENCE, "3")
                .containsEntry(TraceContext.EVENT_ID, event.id().toString());
        // consumer thread is left clean for the next event
        MDC.clear();
        bus.publish(event(4, null)).get(5, TimeUnit.SECONDS);
        assertThat(seen.get(1)).doesNotContainKey(TraceContext.IDEMPOTENCY_KEY);
    }

    @Test
    void failingSubscriberCompletesFutureExceptionally() {
        bus.subscribe(e -> { throw new IllegalStateException("boom"); });
        assertThat(bus.publish(event(1, null))).failsWithin(5, TimeUnit.SECONDS);
    }
}
