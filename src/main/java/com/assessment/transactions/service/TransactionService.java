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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {

    static final Duration PROJECTION_TIMEOUT = Duration.ofSeconds(5);

    private final EventStore eventStore;
    private final EventBus eventBus;
    private final TransactionRepository repository;
    private final TransactionProjector projector;
    private final Clock clock;

    public TransactionService(EventStore eventStore, EventBus eventBus, TransactionRepository repository,
                              TransactionProjector projector, Clock clock) {
        this.eventStore = eventStore;
        this.eventBus = eventBus;
        this.repository = repository;
        this.projector = projector;
        this.clock = clock;
    }

    /**
     * Appends a {@code CREATED} event, emits it, and waits for the consumer to project it so the
     * response already reflects the new transaction in its initial {@code NEW} state.
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
        eventBus.publish(created).orTimeout(PROJECTION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
        return get(created.transactionId());
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
        return projector.replay(id).orElseThrow(() -> new TransactionNotFoundException(id));
    }
}
