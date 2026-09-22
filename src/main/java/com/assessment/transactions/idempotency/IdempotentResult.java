package com.assessment.transactions.idempotency;

import org.springframework.http.HttpStatus;

/** Outcome of an idempotent operation: the HTTP status and body to return, and whether it was replayed. */
public record IdempotentResult<T>(int status, T body, boolean replayed) {

    public static <T> IdempotentResult<T> created(T body) {
        return new IdempotentResult<>(HttpStatus.CREATED.value(), body, false);
    }

    public static <T> IdempotentResult<T> accepted(T body) {
        return new IdempotentResult<>(HttpStatus.ACCEPTED.value(), body, false);
    }

    IdempotentResult<T> asReplay() {
        return new IdempotentResult<>(status, body, true);
    }
}
