package com.assessment.transactions.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model of a transaction, derived purely from its event stream (event sourcing).
 *
 * @param version sequence number of the last event applied; lets {@link #apply} skip events it has
 *                already seen (idempotent) and detect gaps (ordering)
 */
public record Transaction(
        UUID id,
        String accountId,
        BigDecimal amount,
        String currency,
        String reference,
        TransactionStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static final String ACCOUNT_ID = "accountId";
    public static final String AMOUNT = "amount";
    public static final String CURRENCY = "currency";
    public static final String REFERENCE = "reference";

    /** Rebuilds the transaction from its complete stream; empty when the stream is empty. */
    public static Optional<Transaction> replay(List<TransactionEvent> stream) {
        Transaction state = null;
        for (TransactionEvent event : stream) {
            state = state == null ? from(event) : state.apply(event);
        }
        return Optional.ofNullable(state);
    }

    /** Initial state from the {@link EventType#CREATED} event that starts every stream. */
    public static Transaction from(TransactionEvent created) {
        if (created.type() != EventType.CREATED) {
            throw new IllegalStateException("Event stream of " + created.transactionId()
                    + " must start with CREATED but starts with " + created.type());
        }
        Map<String, Object> p = created.payload();
        return new Transaction(
                created.transactionId(),
                (String) p.get(ACCOUNT_ID),
                new BigDecimal(String.valueOf(p.get(AMOUNT))),
                (String) p.get(CURRENCY),
                (String) p.get(REFERENCE),
                TransactionStatus.NEW,
                created.recordedAt(),
                created.recordedAt(),
                created.sequence());
    }

    /**
     * Applies the next event. Events at or below {@link #version} were already applied and are
     * ignored; an event further ahead than {@code version + 1} means the stream was read out of
     * order and is rejected.
     */
    public Transaction apply(TransactionEvent event) {
        if (event.sequence() <= version) {
            return this;
        }
        if (event.sequence() != version + 1) {
            throw new IllegalStateException("Out-of-order event for transaction " + id
                    + ": expected sequence " + (version + 1) + " but got " + event.sequence());
        }
        if (event.type() == EventType.CREATED) {
            throw new IllegalStateException("Transaction " + id + " was already created");
        }
        return new Transaction(id, accountId, amount, currency, reference,
                event.type().targetStatus(), createdAt, event.recordedAt(), event.sequence());
    }
}
