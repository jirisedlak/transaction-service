package com.assessment.transactions.domain;

import java.util.Optional;

/**
 * Types of events in a transaction's stream. {@link #CREATED} is emitted internally when a
 * transaction is POSTed; the others are lifecycle events posted to {@code /events}. Each event
 * moves the transaction to the matching status.
 */
public enum EventType {
    CREATED(TransactionStatus.NEW),
    APPROVED(TransactionStatus.APPROVED),
    SUBMITTED(TransactionStatus.SUBMITTED),
    RESERVED(TransactionStatus.RESERVED),
    SETTLED(TransactionStatus.SETTLED),
    REVERSED(TransactionStatus.REVERSED);

    private final TransactionStatus targetStatus;

    EventType(TransactionStatus targetStatus) {
        this.targetStatus = targetStatus;
    }

    public TransactionStatus targetStatus() {
        return targetStatus;
    }

    /** Whether clients may post this event through the API. */
    public boolean isLifecycleEvent() {
        return this != CREATED;
    }

    /** The event expected to follow this one on the happy path; empty where nothing is required. */
    public Optional<EventType> expectedNext() {
        return switch (this) {
            case CREATED -> Optional.of(APPROVED);   // a transaction needs to be approved first
            case APPROVED -> Optional.of(SUBMITTED); // ...and can then be submitted
            case RESERVED -> Optional.of(SETTLED);
            case SUBMITTED, SETTLED, REVERSED -> Optional.empty();
        };
    }
}
