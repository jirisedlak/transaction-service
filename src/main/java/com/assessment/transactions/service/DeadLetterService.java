package com.assessment.transactions.service;

import com.assessment.transactions.domain.Transaction;
import com.assessment.transactions.eventsourcing.DeadLetter;
import com.assessment.transactions.eventsourcing.DeadLetterStore;
import com.assessment.transactions.eventsourcing.EventBus;
import com.assessment.transactions.logging.TraceContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterStore store;
    private final EventBus eventBus;
    private final TransactionService transactionService;
    private final TransactionProperties properties;

    public DeadLetterService(DeadLetterStore store, EventBus eventBus, TransactionService transactionService,
                             TransactionProperties properties) {
        this.store = store;
        this.eventBus = eventBus;
        this.transactionService = transactionService;
        this.properties = properties;
    }

    public List<DeadLetter> list() {
        return store.findAll();
    }

    public DeadLetter get(UUID eventId) {
        return store.find(eventId).orElseThrow(() -> new DeadLetterNotFoundException(eventId));
    }

    /**
     * Takes the event out of the queue and publishes it again through the normal consumer path
     * (same retries, same dead-lettering if it still fails). Waits for the outcome.
     *
     * @return the transaction read model after a successful redelivery
     * @throws RedeliveryFailedException if consumption failed again (the event is parked again)
     */
    public Transaction redeliver(UUID eventId) {
        DeadLetter deadLetter = store.remove(eventId).orElseThrow(() -> new DeadLetterNotFoundException(eventId));
        try (TraceContext.Scope ignored = TraceContext.with(TraceContext.TRANSACTION_ID, deadLetter.event().transactionId())) {
            log.info("Redelivering dead-lettered {} (seq {}) previously failed with: {}",
                    deadLetter.event().type(), deadLetter.event().sequence(), deadLetter.error());
            try {
                eventBus.publish(deadLetter.event())
                        .orTimeout(properties.projectionTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .join();
            } catch (CompletionException | CancellationException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new RedeliveryFailedException(eventId, cause);
            }
            return transactionService.get(deadLetter.event().transactionId());
        }
    }

    public static class DeadLetterNotFoundException extends RuntimeException {
        public DeadLetterNotFoundException(UUID eventId) {
            super("No dead-lettered event with id " + eventId);
        }
    }

    public static class RedeliveryFailedException extends RuntimeException {
        public RedeliveryFailedException(UUID eventId, Throwable cause) {
            super("Redelivery of event " + eventId + " failed again: " + cause, cause);
        }
    }
}
