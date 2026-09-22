package com.assessment.transactions.api.dto;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.eventsourcing.DeadLetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "DeadLetter", description = "An event the consumer could not process after all retries")
public record DeadLetterResponse(
        UUID eventId,
        UUID transactionId,
        long sequence,
        EventType type,
        @Schema(description = "Last failure", example = "java.lang.NumberFormatException: For input string: \"abc\"") String error,
        @Schema(example = "3") int attempts,
        Instant failedAt,
        @Schema(description = "Correlation id of the request that produced the event", nullable = true) String correlationId) {

    public static DeadLetterResponse from(DeadLetter d) {
        var e = d.event();
        return new DeadLetterResponse(e.id(), e.transactionId(), e.sequence(), e.type(), d.error(), d.attempts(), d.failedAt(), e.correlationId());
    }
}
