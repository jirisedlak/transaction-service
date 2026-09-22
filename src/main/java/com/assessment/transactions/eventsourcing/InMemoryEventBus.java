package com.assessment.transactions.eventsourcing;

import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.logging.TraceContext;
import com.assessment.transactions.observability.ServiceMetrics;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Single-consumer-thread bus with a FIFO queue: events are handled strictly in the order they
 * were published, one at a time.
 *
 * <p>Failure policy: a delivery that throws is retried up to {@code events.consumer.max-attempts}
 * times (with {@code retry-backoff} in between) and then parked in the {@link DeadLetterStore}.
 * The publish future completes exceptionally in that case. The queue is never blocked by a
 * poison event.
 *
 * <p>Shutdown: no new events are accepted, already-published ones are drained for up to
 * {@code events.consumer.drain-timeout} (Spring's graceful shutdown has stopped HTTP traffic first).
 */
@Component
public class InMemoryEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final List<Consumer<TransactionEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final DeadLetterStore deadLetters;
    private final ConsumerProperties properties;
    private final ServiceMetrics metrics;
    private final Clock clock;
    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final ThreadPoolExecutor consumerThread;

    @Autowired
    public InMemoryEventBus(DeadLetterStore deadLetters, ConsumerProperties properties, ServiceMetrics metrics, Clock clock) {
        this.deadLetters = deadLetters;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.consumerThread = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue, r -> {
            Thread t = new Thread(r, "transaction-event-consumer");
            t.setDaemon(true);
            return t;
        });
    }

    /** Defaults for tests: in-memory dead letters, 3 attempts, no backoff, throw-away metrics. */
    public InMemoryEventBus() {
        this(new InMemoryDeadLetterStore(), new ConsumerProperties(3, Duration.ZERO, Duration.ofSeconds(2)),
                ServiceMetrics.inMemory(), Clock.systemUTC());
    }

    public DeadLetterStore deadLetters() {
        return deadLetters;
    }

    @Override
    public CompletableFuture<Void> publish(TransactionEvent event) {
        Map<String, String> publisherContext = TraceContext.snapshot();
        log.debug("Published {} (seq {}) of transaction {}", event.type(), event.sequence(), event.transactionId());
        return CompletableFuture.runAsync(() -> consume(event, publisherContext), consumerThread);
    }

    @Override
    public void subscribe(Consumer<TransactionEvent> subscriber) {
        subscribers.add(subscriber);
    }

    @Override
    public CompletableFuture<Void> ping() {
        return CompletableFuture.runAsync(() -> { }, consumerThread);
    }

    @Override
    public boolean isRunning() {
        return !consumerThread.isShutdown();
    }

    @Override
    public int queueDepth() {
        return queue.size();
    }

    /** Runs on the consumer thread with the publisher's tracing context plus the event's identifiers. */
    private void consume(TransactionEvent event, Map<String, String> publisherContext) {
        try (TraceContext.Scope publisher = TraceContext.restore(publisherContext);
             TraceContext.Scope eventScope = TraceContext.forEvent(event)) {
            int maxAttempts = Math.max(1, properties.maxAttempts());
            for (int attempt = 1; ; attempt++) {
                log.debug("Consuming {} (seq {}), attempt {}/{}", event.type(), event.sequence(), attempt, maxAttempts);
                try {
                    subscribers.forEach(s -> s.accept(event));
                    metrics.delivery("success");
                    return;
                } catch (RuntimeException e) {
                    if (attempt >= maxAttempts) {
                        deadLetters.put(new DeadLetter(event, e.toString(), attempt, clock.instant()));
                        metrics.delivery("dead_letter");
                        log.error("Dead-lettered {} (seq {}) of transaction {} after {} attempt(s)",
                                event.type(), event.sequence(), event.transactionId(), attempt, e);
                        throw e;
                    }
                    metrics.delivery("retry");
                    log.warn("Attempt {}/{} failed for {} (seq {}): {}", attempt, maxAttempts, event.type(), event.sequence(), e.toString());
                    pause(properties.retryBackoff());
                }
            }
        }
    }

    private static void pause(Duration backoff) {
        if (backoff.isZero() || backoff.isNegative()) {
            return;
        }
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops accepting events and drains what is already queued (bounded by {@code drain-timeout}). */
    @PreDestroy
    public void shutdown() {
        int pending = queue.size();
        consumerThread.shutdown();
        try {
            if (consumerThread.awaitTermination(properties.drainTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                log.info("Event consumer stopped; {} queued event(s) drained", pending);
            } else {
                int left = queue.size();
                consumerThread.shutdownNow();
                log.warn("Event consumer stopped with {} event(s) still queued after {}; they remain in the event store and will be projected on the next event or replay",
                        left, properties.drainTimeout());
            }
        } catch (InterruptedException e) {
            consumerThread.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
