package com.assessment.transactions.idempotency;

public abstract class IdempotencyException extends RuntimeException {

    protected IdempotencyException(String message) {
        super(message);
    }

    /** Header value was missing, blank, or too long. */
    public static class InvalidKey extends IdempotencyException {
        public InvalidKey(String message) {
            super(message);
        }
    }

    /** Same key re-used with a different request body. */
    public static class KeyReuse extends IdempotencyException {
        public KeyReuse(String key) {
            super("Idempotency-Key '" + key + "' was already used with a different request payload");
        }
    }

    /** Original request with this key is still being processed. */
    public static class InProgress extends IdempotencyException {
        public InProgress(String key) {
            super("A request with Idempotency-Key '" + key + "' is still being processed");
        }
    }
}
