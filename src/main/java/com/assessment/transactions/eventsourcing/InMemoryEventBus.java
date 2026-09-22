package com.assessment.transactions.eventsourcing;

import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.logging.TraceContext;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * poison event; later events for the same transaction are still delivered, and because the
 * projector catches up from the event store, a later successful delivery also applies the
 * parked event's effect if it is applicable.
 */
@Component
public class InMemoryEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final List<Consumer<TransactionEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final DeadLetterStore deadLetters;
    private final ConsumerProperties properties;
    private final Clock clock;
    private final ExecutorService consumerThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "transaction-event-consumer");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public InMemoryEventBus(DeadLetterStore deadLetters, ConsumerProperties properties, Clock clock) {
        this.deadLetters = deadLetters;
        this.properties = properties;
        this.clock = clock;
    }

    /** Defaults for tests: in-memory dead letters, 3 attempts, no backoff. */
    public InMemoryEventBus() {
        this(new InMemoryDeadLetterStore(), new ConsumerProperties(3, Duration.ZERO), Clock.systemUTC());
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

    /** Runs on the consumer thread with the publisher's tracing context plus the event's identifiers. */
    private void consume(TransactionEvent event, Map<String, String> publisherContext) {
        try (TraceContext.Scope publisher = TraceContext.restore(publisherContext);
             TraceContext.Scope eventScope = TraceContext.forEvent(event)) {
            int maxAttempts = Math.max(1, properties.maxAttempts());
            for (int attempt = 1; ; attempt++) {
                log.debug("Consuming {} (seq {}), attempt {}/{}", event.type(), event.sequence(), attempt, maxAttempts);
                try {
                    subscribers.forEach(s -> s.accept(event));
                    return;
                } catch (RuntimeException e) {
                    if (attempt >= maxAttempts) {
                        deadLetters.put(new DeadLetter(event, e.toString(), attempt, clock.instant()));
                        log.error("Dead-lettered {} (seq {}) of transaction {} after {} attempt(s)",
                                event.type(), event.sequence(), event.transactionId(), attempt, e);
                        throw e;
                    }
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

    @PreDestroy
    void shutdown() {
        consumerThread.shutdown();
    }
}
