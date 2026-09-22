package com.assessment.transactions.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateTransactionRequest(
        @Schema(description = "Account the transaction belongs to", example = "acc-1")
        @NotBlank @Size(max = 64) String accountId,
        @Schema(description = "Positive amount, at most 15 integer and 4 fraction digits", example = "100.50")
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @Schema(description = "3-letter ISO 4217 code (stored upper-cased)", example = "EUR")
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO 4217 code") String currency,
        @Schema(description = "Free-form client reference", example = "order-42", nullable = true)
        @Size(max = 255) String reference) {
}
