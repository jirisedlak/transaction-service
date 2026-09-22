package com.assessment.transactions.eventsourcing;

import static org.assertj.core.api.Assertions.assertThat;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.logging.TraceContext;
import java.time.Duration;
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
    void failingSubscriberIsRetriedThenDeadLettered() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        bus.subscribe(e -> { if (e.sequence() == 1) { calls.incrementAndGet(); throw new IllegalStateException("boom"); } });
        TransactionEvent poison = event(1, "cid-poison");

        assertThat(bus.publish(poison)).failsWithin(5, TimeUnit.SECONDS);

        assertThat(calls).hasValue(3);
        assertThat(bus.deadLetters().findAll()).singleElement().satisfies(d -> {
            assertThat(d.event()).isEqualTo(poison);
            assertThat(d.attempts()).isEqualTo(3);
            assertThat(d.error()).contains("boom");
        });
        // the queue is not blocked: the next event is still delivered
        List<TransactionEvent> seen = new CopyOnWriteArrayList<>();
        bus.subscribe(seen::add);
        bus.publish(event(2, null)).get(5, TimeUnit.SECONDS);
        assertThat(seen).extracting(TransactionEvent::sequence).containsExactly(2L);
    }

    @Test
    void shutdownDrainsQueuedEventsThenRefusesNewOnes() throws Exception {
        List<Long> seen = new CopyOnWriteArrayList<>();
        bus.subscribe(e -> {
            try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            seen.add(e.sequence());
        });
        for (long i = 1; i <= 3; i++) {
            bus.publish(event(i, null));
        }
        assertThat(bus.isRunning()).isTrue();

        bus.shutdown(); // drain-timeout in the test constructor is 2s, enough for 3 x 100ms

        assertThat(seen).containsExactly(1L, 2L, 3L);
        assertThat(bus.isRunning()).isFalse();
        assertThat(bus.queueDepth()).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bus.publish(event(4, null)))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    }

    @Test
    void pingCompletesOnConsumerThreadAndQueueDepthReflectsBacklog() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        bus.subscribe(e -> {
            try { release.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        });
        bus.publish(event(1, null));            // occupies the consumer
        bus.publish(event(2, null));            // queued
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> bus.queueDepth() == 1);
        java.util.concurrent.CompletableFuture<Void> ping = bus.ping();
        assertThat(ping).isNotDone();          // behind the backlog

        release.countDown();
        ping.get(5, TimeUnit.SECONDS);
        assertThat(bus.queueDepth()).isZero();
    }

    @Test
    void transientFailureSucceedsOnRetryWithoutDeadLettering() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        bus.subscribe(e -> { if (calls.incrementAndGet() < 3) throw new IllegalStateException("flaky"); });

        bus.publish(event(1, null)).get(5, TimeUnit.SECONDS);

        assertThat(calls).hasValue(3);
        assertThat(bus.deadLetters().findAll()).isEmpty();
    }

    @Test
    void redeliveryOfDeadLetterGoesThroughNormalPath() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean healthy = new java.util.concurrent.atomic.AtomicBoolean(false);
        bus.subscribe(e -> { if (!healthy.get()) throw new IllegalStateException("down"); });
        TransactionEvent event = event(1, null);
        assertThat(bus.publish(event)).failsWithin(5, TimeUnit.SECONDS);
        assertThat(bus.deadLetters().find(event.id())).isPresent();

        healthy.set(true);
        var parked = bus.deadLetters().remove(event.id()).orElseThrow();
        bus.publish(parked.event()).get(5, TimeUnit.SECONDS);
        assertThat(bus.deadLetters().findAll()).isEmpty();
    }
}
