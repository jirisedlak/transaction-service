package com.assessment.transactions.service;

import com.assessment.transactions.api.dto.PostEventRequest;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.eventsourcing.EventBus;
import com.assessment.transactions.eventsourcing.EventStore;
import com.assessment.transactions.logging.TraceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TransactionEventService {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventService.class);

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
        try (TraceContext.Scope ignored = TraceContext.with(TraceContext.TRANSACTION_ID, request.transactionId())) {
            Instant now = clock.instant();
            TransactionEvent event = eventStore.append(
                    request.transactionId(),
                    request.type(),
                    request.payload() == null ? Map.of() : request.payload(),
                    request.occurredAt() == null ? now : request.occurredAt(),
                    now);
            log.info("Accepted {} event as sequence {}", event.type(), event.sequence());
            eventBus.publish(event);
            return event;
        }
    }
}
