package com.assessment.transactions.api.dto;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.TransactionEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Schema(name = "Event", description = "One entry in a transaction's event stream")
public record EventResponse(
        @Schema(example = "0b770b35-aa5a-4876-9244-866a8284b515") UUID id,
        @Schema(example = "40e3dfd8-2aad-4161-b12f-dff43506ca00") UUID transactionId,
        @Schema(description = "1-based position in the transaction's stream; fixes the receive order", example = "2") long sequence,
        @Schema(description = "`CREATED` starts every stream; the others are lifecycle events") EventType type,
        @Schema(description = "For `CREATED`, the original request data (accountId, amount, currency, reference)", example = "{\"approver\":\"risk-engine\"}") Map<String, Object> payload,
        @Schema(example = "2026-09-22T08:29:01.814166Z") Instant occurredAt,
        @Schema(example = "2026-09-22T08:29:01.814166Z") Instant recordedAt,
        @Schema(description = "Correlation id of the request that produced the event", example = "sample-1790065741-05", nullable = true) String correlationId) {

    public static EventResponse from(TransactionEvent e) {
        return new EventResponse(e.id(), e.transactionId(), e.sequence(), e.type(), e.payload(), e.occurredAt(), e.recordedAt(),
                e.correlationId());
    }
}
