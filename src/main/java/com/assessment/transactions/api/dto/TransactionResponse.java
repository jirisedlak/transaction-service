package com.assessment.transactions.api.dto;

import com.assessment.transactions.domain.Transaction;
import com.assessment.transactions.domain.TransactionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "Transaction", description = "Read model of a transaction, derived from its event stream")
public record TransactionResponse(
        @Schema(example = "40e3dfd8-2aad-4161-b12f-dff43506ca00") UUID id,
        @Schema(example = "acc-1") String accountId,
        @Schema(example = "100.50") BigDecimal amount,
        @Schema(example = "EUR") String currency,
        @Schema(example = "order-42", nullable = true) String reference,
        @Schema(description = "Derived from the last applied event. Terminal: SUBMITTED, APPROVED, SETTLED, REVERSED") TransactionStatus status,
        @Schema(example = "2026-09-22T08:29:01.476088Z") Instant createdAt,
        @Schema(description = "Recorded time of the last applied event", example = "2026-09-22T08:29:01.940993Z") Instant updatedAt,
        @Schema(description = "Sequence number of the last event applied to this read model", example = "3") long version) {

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(t.id(), t.accountId(), t.amount(), t.currency(), t.reference(), t.status(),
                t.createdAt(), t.updatedAt(), t.version());
    }
}
