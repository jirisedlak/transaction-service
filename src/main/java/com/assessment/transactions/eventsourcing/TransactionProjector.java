package com.assessment.transactions.eventsourcing;

import com.assessment.transactions.domain.Transaction;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.domain.TransactionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The internal event consumer. On every event it folds the transaction's stream into the
 * persistent {@link Transaction} read model.
 *
 * <p>Idempotent and order-safe: it never trusts the notification itself. It reads from the
 * {@link EventStore} everything after the projection's current {@code version} and applies it in
 * sequence order, under the repository's per-transaction lock. A duplicate notification finds
 * nothing new; an early notification simply applies more.
 */
@Component
public class TransactionProjector {

    private final EventStore eventStore;
    private final TransactionRepository repository;

    public TransactionProjector(EventStore eventStore, TransactionRepository repository, EventBus eventBus) {
        this.eventStore = eventStore;
        this.repository = repository;
        eventBus.subscribe(this::onEvent);
    }

    void onEvent(TransactionEvent event) {
        project(event.transactionId());
    }

    /** Catches the projection up with the stream. */
    public Optional<Transaction> project(UUID transactionId) {
        return repository.compute(transactionId, current -> {
            long version = current == null ? 0 : current.version();
            List<TransactionEvent> pending = eventStore.streamAfter(transactionId, version);
            Transaction state = current;
            for (TransactionEvent event : pending) {
                state = state == null ? Transaction.from(event) : state.apply(event);
            }
            return state;
        });
    }

    /** Discards the projection and rebuilds it by replaying the whole stream from the beginning. */
    public Optional<Transaction> replay(UUID transactionId) {
        return repository.compute(transactionId,
                current -> Transaction.replay(eventStore.stream(transactionId)).orElse(null));
    }
}
