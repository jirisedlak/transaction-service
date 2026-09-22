package com.assessment.transactions.api.dto;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.TransactionEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record EventResponse(
        UUID id,
        UUID transactionId,
        long sequence,
        EventType type,
        Map<String, Object> payload,
        Instant occurredAt,
        Instant recordedAt) {

    public static EventResponse from(TransactionEvent e) {
        return new EventResponse(e.id(), e.transactionId(), e.sequence(), e.type(), e.payload(), e.occurredAt(), e.recordedAt());
    }
}
