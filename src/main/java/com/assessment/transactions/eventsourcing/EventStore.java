package com.assessment.transactions.eventsourcing;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.logging.TraceContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Append-only, per-transaction event streams: the system of record. Sequence numbers are
 * assigned on append and are contiguous within a stream, which fixes the order of events.
 */
public interface EventStore {

    /**
     * Appends an event to the transaction's stream and assigns the next sequence number.
     * Only {@link EventType#CREATED} may start a stream, and only as its first event; any other type
     * on a transaction without a stream throws
     * {@link com.assessment.transactions.domain.TransactionNotFoundException}.
     */
    TransactionEvent append(UUID transactionId, EventType type, Map<String, Object> payload,
                            Instant occurredAt, Instant recordedAt, String correlationId);

    /** Appends with the correlation id of the current tracing scope. */
    default TransactionEvent append(UUID transactionId, EventType type, Map<String, Object> payload,
                                    Instant occurredAt, Instant recordedAt) {
        return append(transactionId, type, payload, occurredAt, recordedAt, TraceContext.correlationId());
    }

    /** The complete stream in sequence order; empty if the transaction is unknown. */
    List<TransactionEvent> stream(UUID transactionId);

    /** Events with {@code sequence > afterSequence}, in sequence order. */
    List<TransactionEvent> streamAfter(UUID transactionId, long afterSequence);

    Set<UUID> transactionIds();
}
