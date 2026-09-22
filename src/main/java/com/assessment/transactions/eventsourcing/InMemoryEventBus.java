package com.assessment.transactions.eventsourcing;

import com.assessment.transactions.domain.TransactionEvent;
import jakarta.annotation.PreDestroy;
import java.util.List;
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
        return CompletableFuture
                .runAsync(() -> subscribers.forEach(s -> s.accept(event)), consumerThread)
                .whenComplete((v, error) -> {
                    if (error != null) {
                        log.error("Consumer failed for event {} (seq {}) of transaction {}",
                                event.type(), event.sequence(), event.transactionId(), error);
                    }
                });
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
