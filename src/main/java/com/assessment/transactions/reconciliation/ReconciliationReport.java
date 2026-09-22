package com.assessment.transactions.reconciliation;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.TransactionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationReport(
        Instant asOf,
        Duration staleAfter,
        List<StaleTransaction> staleTransactions,
        List<DuplicateEvent> duplicateEvents,
        List<MissingTransition> missingTransitions) {

    /** Non-terminal transaction older than {@code staleAfter}. */
    public record StaleTransaction(UUID transactionId, TransactionStatus status, Instant createdAt, Duration age) {
    }

    /** The same event type recorded more than once for a transaction. */
    public record DuplicateEvent(UUID transactionId, EventType eventType, int occurrences) {
    }

    /** An event was recorded but the event expected to follow it never was. */
    public record MissingTransition(UUID transactionId, TransactionStatus status, EventType recorded, EventType expectedNext) {
    }

    public int findingCount() {
        return staleTransactions.size() + duplicateEvents.size() + missingTransitions.size();
    }
}
