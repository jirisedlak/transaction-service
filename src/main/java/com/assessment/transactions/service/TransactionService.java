package com.assessment.transactions.service;

import com.assessment.transactions.api.dto.CreateTransactionRequest;
import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.Transaction;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.domain.TransactionNotFoundException;
import com.assessment.transactions.domain.TransactionRepository;
import com.assessment.transactions.eventsourcing.EventBus;
import com.assessment.transactions.eventsourcing.EventStore;
import com.assessment.transactions.eventsourcing.TransactionProjector;
import com.assessment.transactions.logging.TraceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final EventStore eventStore;
    private final EventBus eventBus;
    private final TransactionRepository repository;
    private final TransactionProjector projector;
    private final TransactionProperties properties;
    private final Clock clock;

    public TransactionService(EventStore eventStore, EventBus eventBus, TransactionRepository repository,
                              TransactionProjector projector, TransactionProperties properties, Clock clock) {
        this.eventStore = eventStore;
        this.eventBus = eventBus;
        this.repository = repository;
        this.projector = projector;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Appends a {@code CREATED} event, emits it, and waits (bounded by
     * {@code transactions.projection-timeout}) for the consumer to project it so the response
     * reflects the read model.
     *
     * <p>Once the event is appended the operation has succeeded, whatever the consumer does next:
     * if the projection is not confirmed in time (or fails and is dead-lettered) the response is
     * built from the event itself and the read model catches up later. The request therefore
     * never fails after the append, the idempotency record is completed, and a client retry with
     * the same key replays this response instead of creating a second stream.
     */
    public Transaction create(CreateTransactionRequest request) {
        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(Transaction.ACCOUNT_ID, request.accountId());
        payload.put(Transaction.AMOUNT, request.amount());
        payload.put(Transaction.CURRENCY, request.currency().toUpperCase());
        if (request.reference() != null) {
            payload.put(Transaction.REFERENCE, request.reference());
        }

        TransactionEvent created = eventStore.append(UUID.randomUUID(), EventType.CREATED, payload, now, now);
        try (TraceContext.Scope ignored = TraceContext.with(TraceContext.TRANSACTION_ID, created.transactionId())) {
            log.info("Created transaction for account {} ({} {}), waiting for projection", request.accountId(),
                    request.amount(), payload.get(Transaction.CURRENCY));
            try {
                eventBus.publish(created)
                        .orTimeout(properties.projectionTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .join();
            } catch (CompletionException | CancellationException e) {
                log.warn("Projection of CREATED not confirmed within {} ({}); answering from the event, read model will catch up",
                        properties.projectionTimeout(), e.getCause() == null ? e : e.getCause().toString());
            }
            return repository.findById(created.transactionId()).orElseGet(() -> Transaction.from(created));
        }
    }

    public Transaction get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new TransactionNotFoundException(id));
    }

    /** The transaction's full event stream in order. */
    public List<TransactionEvent> events(UUID id) {
        List<TransactionEvent> stream = eventStore.stream(id);
        if (stream.isEmpty()) {
            throw new TransactionNotFoundException(id);
        }
        return stream;
    }

    /** Rebuilds the read model from the event stream. */
    public Transaction replay(UUID id) {
        log.info("Replay requested for transaction {}", id);
        return projector.replay(id).orElseThrow(() -> new TransactionNotFoundException(id));
    }
}
