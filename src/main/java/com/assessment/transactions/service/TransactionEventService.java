package com.assessment.transactions.service;

import com.assessment.transactions.api.dto.PostEventRequest;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.eventsourcing.EventBus;
import com.assessment.transactions.eventsourcing.EventStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class TransactionEventService {

    private final EventStore eventStore;
    private final EventBus eventBus;
    private final Clock clock;

    public TransactionEventService(EventStore eventStore, EventBus eventBus, Clock clock) {
        this.eventStore = eventStore;
        this.eventBus = eventBus;
        this.clock = clock;
    }

    /**
     * Appends the event to the transaction's stream (404 if there is no such stream) and emits it.
     * The transaction read model is updated asynchronously by the consumer, in stream order.
     */
    public TransactionEvent post(PostEventRequest request) {
        Instant now = clock.instant();
        TransactionEvent event = eventStore.append(
                request.transactionId(),
                request.type(),
                request.payload() == null ? Map.of() : request.payload(),
                request.occurredAt() == null ? now : request.occurredAt(),
                now);
        eventBus.publish(event);
        return event;
    }
}
