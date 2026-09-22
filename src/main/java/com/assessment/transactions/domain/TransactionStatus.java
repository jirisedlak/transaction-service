package com.assessment.transactions.domain;

/**
 * Status of a transaction, derived from the last event in its stream. Every POSTed transaction
 * starts as {@link #NEW}; each event moves it to the status of the same name (see {@link EventType}).
 *
 * <p>Expected path: {@code NEW -> APPROVED -> SUBMITTED}. {@code RESERVED -> SETTLED} and
 * {@code REVERSED} are the remaining branches. Terminal statuses are the ones the reconciliation
 * report accepts as a finished transaction.
 */
public enum TransactionStatus {
    NEW,
    APPROVED,
    SUBMITTED,
    RESERVED,
    SETTLED,
    REVERSED;

    public boolean isTerminal() {
        return switch (this) {
            case SUBMITTED, APPROVED, SETTLED, REVERSED -> true;
            case NEW, RESERVED -> false;
        };
    }
}
