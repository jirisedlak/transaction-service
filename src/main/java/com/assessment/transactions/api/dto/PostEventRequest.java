package com.assessment.transactions.api.dto;

import com.assessment.transactions.domain.EventType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PostEventRequest(
        @NotNull UUID transactionId,
        @NotNull EventType type,
        Map<String, Object> payload,
        Instant occurredAt) {

    @AssertTrue(message = "CREATED is emitted internally and cannot be posted")
    public boolean isPostableType() {
        return type == null || type.isLifecycleEvent();
    }
}
