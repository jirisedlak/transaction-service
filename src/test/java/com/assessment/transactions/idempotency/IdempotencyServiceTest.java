package com.assessment.transactions.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdempotencyServiceTest {

    private final IdempotencyService service =
            new IdempotencyService(new InMemoryIdempotencyStore(), new RequestFingerprinter(new ObjectMapper()));

    @Test
    void runsOperationOnceForSameKeyAndPayload() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> request = Map.of("a", 1);

        IdempotentResult<String> first = service.execute("s", "k", request, () -> IdempotentResult.created("r" + calls.incrementAndGet()));
        IdempotentResult<String> second = service.execute("s", "k", request, () -> IdempotentResult.created("r" + calls.incrementAndGet()));

        assertThat(calls).hasValue(1);
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.body()).isEqualTo("r1");
        assertThat(second.status()).isEqualTo(201);
    }

    @Test
    void keysAreScoped() {
        AtomicInteger calls = new AtomicInteger();
        service.execute("transaction", "k", Map.of(), () -> IdempotentResult.created(calls.incrementAndGet()));
        service.execute("events", "k", Map.of(), () -> IdempotentResult.created(calls.incrementAndGet()));
        assertThat(calls).hasValue(2);
    }

    @Test
    void fingerprintIgnoresMapKeyOrder() {
        AtomicInteger calls = new AtomicInteger();
        service.execute("s", "k", Map.of("a", 1, "b", 2), () -> IdempotentResult.created(calls.incrementAndGet()));
        service.execute("s", "k", Map.of("b", 2, "a", 1), () -> IdempotentResult.created(calls.incrementAndGet()));
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsSameKeyWithDifferentPayload() {
        service.execute("s", "k", Map.of("a", 1), () -> IdempotentResult.created("x"));
        assertThatThrownBy(() -> service.execute("s", "k", Map.of("a", 2), () -> IdempotentResult.created("y")))
                .isInstanceOf(IdempotencyException.KeyReuse.class);
    }

    @Test
    void rejectsBlankOrOverlongKey() {
        assertThatThrownBy(() -> service.execute("s", " ", Map.of(), () -> IdempotentResult.created("x")))
                .isInstanceOf(IdempotencyException.InvalidKey.class);
        assertThatThrownBy(() -> service.execute("s", "x".repeat(256), Map.of(), () -> IdempotentResult.created("x")))
                .isInstanceOf(IdempotencyException.InvalidKey.class);
    }

    @Test
    void releasesKeyWhenOperationFails() {
        assertThatThrownBy(() -> service.execute("s", "k", Map.of(), () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);

        IdempotentResult<String> retry = service.execute("s", "k", Map.of(), () -> IdempotentResult.created("ok"));
        assertThat(retry.replayed()).isFalse();
        assertThat(retry.body()).isEqualTo("ok");
    }

    @Test
    void concurrentDuplicateWhileInProgressIsConflict() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<IdempotentResult<String>> slow = pool.submit(() -> service.execute("s", "k", Map.of(), () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return IdempotentResult.created("slow");
            }));
            started.await();

            assertThatThrownBy(() -> service.execute("s", "k", Map.of(), () -> IdempotentResult.created("fast")))
                    .isInstanceOf(IdempotencyException.InProgress.class);

            release.countDown();
            assertThat(slow.get().body()).isEqualTo("slow");
        } finally {
            pool.shutdownNow();
        }
    }
}
