package com.assessment.transactions.eventsourcing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.domain.TransactionNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class InMemoryEventStoreTest {

    private static final Instant T0 = Instant.parse("2026-09-22T10:00:00Z");
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final UUID txId = UUID.randomUUID();

    private static Map<String, Object> created() {
        return Map.of("accountId", "acc", "amount", 1, "currency", "EUR");
    }

    @Test
    void assignsContiguousSequenceNumbersInAppendOrder() {
        store.append(txId, EventType.CREATED, created(), T0, T0);
        store.append(txId, EventType.APPROVED, Map.of(), T0, T0);
        store.append(txId, EventType.SUBMITTED, Map.of(), T0, T0);

        assertThat(store.stream(txId)).extracting(TransactionEvent::sequence).containsExactly(1L, 2L, 3L);
        assertThat(store.stream(txId)).extracting(TransactionEvent::type)
                .containsExactly(EventType.CREATED, EventType.APPROVED, EventType.SUBMITTED);
        assertThat(store.streamAfter(txId, 1)).extracting(TransactionEvent::sequence).containsExactly(2L, 3L);
        assertThat(store.streamAfter(txId, 3)).isEmpty();
        assertThat(store.transactionIds()).containsExactly(txId);
    }

    @Test
    void onlyCreatedMayStartAStream() {
        assertThatThrownBy(() -> store.append(txId, EventType.APPROVED, Map.of(), T0, T0))
                .isInstanceOf(TransactionNotFoundException.class);
        assertThat(store.stream(txId)).isEmpty();
    }

    @Test
    void createdIsOnlyAcceptedAsFirstEvent() {
        store.append(txId, EventType.CREATED, created(), T0, T0);
        assertThatThrownBy(() -> store.append(txId, EventType.CREATED, created(), T0, T0))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.stream(txId)).hasSize(1);
    }

    @Test
    void concurrentAppendsNeverCollideOnSequence() throws Exception {
        store.append(txId, EventType.CREATED, created(), T0, T0);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<? extends java.util.concurrent.Future<?>> futures = IntStream.range(0, 200)
                    .mapToObj(i -> pool.submit(() -> store.append(txId, EventType.APPROVED, Map.of("i", i), T0, T0)))
                    .toList();
            for (var f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        List<TransactionEvent> stream = store.stream(txId);
        assertThat(stream).hasSize(201);
        assertThat(stream).extracting(TransactionEvent::sequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 201).boxed().toList());
    }
}
