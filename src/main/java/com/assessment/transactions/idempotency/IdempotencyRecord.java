package com.assessment.transactions.idempotency;

/**
 * What the store remembers for one (scope, key) pair.
 * {@code status}/{@code body} are {@code null} while the original request is still being processed.
 */
public record IdempotencyRecord(String fingerprint, Integer status, Object body) {

    public static IdempotencyRecord inProgress(String fingerprint) {
        return new IdempotencyRecord(fingerprint, null, null);
    }

    public IdempotencyRecord completed(int status, Object body) {
        return new IdempotencyRecord(fingerprint, status, body);
    }

    public boolean isInProgress() {
        return status == null;
    }
}
