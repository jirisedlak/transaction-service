package com.assessment.transactions.eventsourcing;

import com.assessment.transactions.domain.TransactionEvent;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** In-process publish/subscribe channel between the API and the event consumer. */
public interface EventBus {

    /**
     * Emits an event to all subscribers.
     *
     * @return completes once every subscriber has handled the event (exceptionally if one failed)
     */
    CompletableFuture<Void> publish(TransactionEvent event);

    void subscribe(Consumer<TransactionEvent> subscriber);
}
