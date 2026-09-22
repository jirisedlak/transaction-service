package com.assessment.transactions.eventsourcing;

import com.assessment.transactions.domain.TransactionEvent;
import jakarta.annotation.PreDestroy;
import com.assessment.transactions.logging.TraceContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single-consumer-thread bus with a FIFO queue: events are handled strictly in the order they
 * were published, one at a time.
 */
@Component
public class InMemoryEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final List<Consumer<TransactionEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final ExecutorService consumerThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "transaction-event-consumer");
        t.setDaemon(true);
        return t;
    });

    @Override
    public CompletableFuture<Void> publish(TransactionEvent event) {
        Map<String, String> publisherContext = TraceContext.snapshot();
        log.debug("Published {} (seq {}) of transaction {}", event.type(), event.sequence(), event.transactionId());
        return CompletableFuture.runAsync(() -> consume(event, publisherContext), consumerThread);
    }

    /** Runs on the consumer thread with the publisher's tracing context plus the event's identifiers. */
    private void consume(TransactionEvent event, Map<String, String> publisherContext) {
        try (TraceContext.Scope publisher = TraceContext.restore(publisherContext);
             TraceContext.Scope eventScope = TraceContext.forEvent(event)) {
            log.debug("Consuming {} (seq {})", event.type(), event.sequence());
            try {
                subscribers.forEach(s -> s.accept(event));
            } catch (RuntimeException e) {
                log.error("Consumer failed for {} (seq {}) of transaction {}", event.type(), event.sequence(), event.transactionId(), e);
                throw e;
            }
        }
    }

    @Override
    public void subscribe(Consumer<TransactionEvent> subscriber) {
        subscribers.add(subscriber);
    }

    @PreDestroy
    void shutdown() {
        consumerThread.shutdown();
    }
}
