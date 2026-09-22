package com.assessment.transactions.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One immutable entry in a transaction's event stream.
 *
 * @param sequence 1-based position in the transaction's stream, assigned by the event store on append
 */
public record TransactionEvent(
        UUID id,
        UUID transactionId,
        long sequence,
        EventType type,
        Map<String, Object> payload,
        Instant occurredAt,
        Instant recordedAt) {
}
