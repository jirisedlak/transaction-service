package com.assessment.transactions.eventsourcing;

import com.assessment.transactions.domain.Transaction;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.domain.TransactionRepository;
import com.assessment.transactions.logging.TraceContext;
import com.assessment.transactions.observability.ServiceMetrics;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger log = LoggerFactory.getLogger(TransactionProjector.class);

    private final EventStore eventStore;
    private final TransactionRepository repository;
    private final ServiceMetrics metrics;

    @Autowired
    public TransactionProjector(EventStore eventStore, TransactionRepository repository, EventBus eventBus, ServiceMetrics metrics) {
        this.eventStore = eventStore;
        this.repository = repository;
        this.metrics = metrics;
        eventBus.subscribe(this::onEvent);
    }

    /** For tests: throw-away metrics. */
    public TransactionProjector(EventStore eventStore, TransactionRepository repository, EventBus eventBus) {
        this(eventStore, repository, eventBus, ServiceMetrics.inMemory());
    }

    void onEvent(TransactionEvent event) {
        project(event.transactionId());
    }

    /** Catches the projection up with the stream. */
    public Optional<Transaction> project(UUID transactionId) {
        try (TraceContext.Scope ignored = TraceContext.with(TraceContext.TRANSACTION_ID, transactionId)) {
            return metrics.timeProjection(() -> repository.compute(transactionId, current -> {
                long version = current == null ? 0 : current.version();
                List<TransactionEvent> pending = eventStore.streamAfter(transactionId, version);
                if (pending.isEmpty()) {
                    log.debug("Projection already at version {}, nothing to apply", version);
                    return current;
                }
                Transaction state = current;
                for (TransactionEvent event : pending) {
                    state = state == null ? Transaction.from(event) : state.apply(event);
                }
                metrics.projectionApplied(pending.size());
                log.info("Applied {} event(s), version {} -> {}, status {}", pending.size(), version, state.version(), state.status());
                return state;
            }));
        }
    }

    /** Discards the projection and rebuilds it by replaying the whole stream from the beginning. */
    public Optional<Transaction> replay(UUID transactionId) {
        try (TraceContext.Scope ignored = TraceContext.with(TraceContext.TRANSACTION_ID, transactionId)) {
            return repository.compute(transactionId, current -> {
                List<TransactionEvent> stream = eventStore.stream(transactionId);
                Transaction rebuilt = Transaction.replay(stream).orElse(null);
                log.info("Replayed {} event(s) from scratch -> {}", stream.size(),
                        rebuilt == null ? "no projection" : "version " + rebuilt.version() + ", status " + rebuilt.status());
                return rebuilt;
            });
        }
    }
}
