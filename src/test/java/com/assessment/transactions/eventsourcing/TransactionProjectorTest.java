package com.assessment.transactions.eventsourcing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.InMemoryTransactionRepository;
import com.assessment.transactions.domain.Transaction;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.domain.TransactionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TransactionProjectorTest {

    private static final Instant T0 = Instant.parse("2026-09-22T10:00:00Z");

    private final InMemoryEventStore store = new InMemoryEventStore();
    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final InMemoryEventBus bus = new InMemoryEventBus();
    private final TransactionProjector projector = new TransactionProjector(store, repository, bus);
    private final UUID txId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        bus.shutdown();
    }

    private TransactionEvent emit(EventType type) {
        Map<String, Object> payload = type == EventType.CREATED
                ? Map.of("accountId", "acc", "amount", 1, "currency", "EUR") : Map.of();
        TransactionEvent e = store.append(txId, type, payload, T0, T0.plusSeconds(store.stream(txId).size()));
        bus.publish(e);
        return e;
    }

    @Test
    void consumerBuildsAndUpdatesProjectionInStreamOrder() {
        emit(EventType.CREATED);
        emit(EventType.APPROVED);
        emit(EventType.SUBMITTED);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(repository.findById(txId)).get()
                        .extracting(Transaction::status, Transaction::version)
                        .containsExactly(TransactionStatus.SUBMITTED, 3L));
    }

    @Test
    void duplicateAndLateNotificationsAreIdempotent() {
        TransactionEvent created = store.append(txId, EventType.CREATED, Map.of("accountId", "a", "amount", 1, "currency", "EUR"), T0, T0);
        TransactionEvent approved = store.append(txId, EventType.APPROVED, Map.of(), T0, T0);

        projector.onEvent(approved);            // arrives before the CREATED notification: catches up both
        projector.onEvent(approved);            // duplicate delivery
        projector.onEvent(created);             // late delivery
        projector.onEvent(created);

        Transaction tx = repository.findById(txId).orElseThrow();
        assertThat(tx.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(tx.version()).isEqualTo(2);
    }

    @Test
    void eventsPublishedConcurrentlyAreAppliedExactlyOnceInSequenceOrder() throws Exception {
        bus.publish(store.append(txId, EventType.CREATED, Map.of("accountId", "a", "amount", 1, "currency", "EUR"), T0, T0));
        EventType[] cycle = {EventType.APPROVED, EventType.SUBMITTED, EventType.RESERVED, EventType.SETTLED, EventType.REVERSED};
        int n = 500;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<Void>> published = new ArrayList<>();
            List<CompletableFuture<Void>> submitted = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                EventType type = cycle[i % cycle.length];
                submitted.add(CompletableFuture.runAsync(() -> {
                    TransactionEvent e = store.append(txId, type, Map.of(), T0, T0);
                    synchronized (published) {
                        published.add(bus.publish(e));
                    }
                }, pool));
            }
            CompletableFuture.allOf(submitted.toArray(CompletableFuture[]::new)).get();
            CompletableFuture.allOf(published.toArray(CompletableFuture[]::new)).get();
        } finally {
            pool.shutdownNow();
        }

        Transaction tx = repository.findById(txId).orElseThrow();
        List<TransactionEvent> stream = store.stream(txId);
        assertThat(tx.version()).isEqualTo(n + 1);
        assertThat(tx.status()).isEqualTo(stream.get(stream.size() - 1).type().targetStatus());
        assertThat(projector.replay(txId)).contains(tx);
    }

    @Test
    void replayRebuildsProjectionFromScratch() {
        store.append(txId, EventType.CREATED, Map.of("accountId", "a", "amount", 1, "currency", "EUR"), T0, T0);
        store.append(txId, EventType.APPROVED, Map.of(), T0, T0.plusSeconds(1));

        assertThat(repository.findById(txId)).isEmpty();
        Transaction rebuilt = projector.replay(txId).orElseThrow();
        assertThat(rebuilt.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(rebuilt.version()).isEqualTo(2);
        assertThat(repository.findById(txId)).contains(rebuilt);

        assertThat(projector.replay(UUID.randomUUID())).isEmpty();
    }
}
