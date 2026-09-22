package com.assessment.transactions.reconciliation;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.TransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationReport(
        @Schema(description = "The instant the report was evaluated at", example = "2026-09-22T08:32:02Z") Instant asOf,
        @Schema(description = "ISO-8601 duration after which a non-terminal transaction is stale", type = "string", example = "PT2M") Duration staleAfter,
        List<StaleTransaction> staleTransactions,
        List<DuplicateEvent> duplicateEvents,
        List<MissingTransition> missingTransitions,
        List<DeadLetteredEvent> deadLetteredEvents) {

    @Schema(description = "Non-terminal transaction older than `staleAfter`")
    public record StaleTransaction(
            UUID transactionId,
            TransactionStatus status,
            Instant createdAt,
            @Schema(description = "ISO-8601 duration since creation, as of `asOf`", type = "string", example = "PT2M59.669621S") Duration age) {
    }

    @Schema(description = "The same event type recorded more than once on a transaction")
    public record DuplicateEvent(UUID transactionId, EventType eventType, @Schema(minimum = "2", example = "2") int occurrences) {
    }

    @Schema(description = "An event was recorded but the event expected to follow it never was")
    public record MissingTransition(UUID transactionId, TransactionStatus status, EventType recorded, EventType expectedNext) {
    }

    @Schema(description = "An event the consumer could not apply; the read model of its transaction is behind the stream")
    public record DeadLetteredEvent(UUID transactionId, UUID eventId, long sequence, EventType type, String error, int attempts, Instant failedAt) {
    }

    @Schema(hidden = true)
    public int findingCount() {
        return staleTransactions.size() + duplicateEvents.size() + missingTransitions.size() + deadLetteredEvents.size();
    }
}
