package com.assessment.transactions.api.dto;

import com.assessment.transactions.domain.EventType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PostEventRequest(
        @Schema(description = "Transaction the event belongs to", example = "40e3dfd8-2aad-4161-b12f-dff43506ca00")
        @NotNull UUID transactionId,
        @Schema(description = "Lifecycle event to record; `CREATED` is emitted internally and cannot be posted",
                allowableValues = {"APPROVED", "SUBMITTED", "RESERVED", "SETTLED", "REVERSED"}, example = "APPROVED")
        @NotNull EventType type,
        @Schema(description = "Free-form data attached to the event", example = "{\"approver\":\"risk-engine\"}", nullable = true)
        Map<String, Object> payload,
        @Schema(description = "When the event happened in the outside world; defaults to the time it was recorded", nullable = true)
        Instant occurredAt) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "CREATED is emitted internally and cannot be posted")
    public boolean isPostableType() {
        return type == null || type.isLifecycleEvent();
    }
}
