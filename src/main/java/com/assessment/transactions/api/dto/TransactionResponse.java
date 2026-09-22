package com.assessment.transactions.api.dto;

import com.assessment.transactions.domain.Transaction;
import com.assessment.transactions.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String accountId,
        BigDecimal amount,
        String currency,
        String reference,
        TransactionStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(t.id(), t.accountId(), t.amount(), t.currency(), t.reference(), t.status(),
                t.createdAt(), t.updatedAt(), t.version());
    }
}
