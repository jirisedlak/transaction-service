package com.assessment.transactions.idempotency;

import java.util.Optional;

/**
 * Storage abstraction for idempotency records. The in-memory implementation is the default;
 * swap for a shared store (database, Redis) when running more than one instance.
 */
public interface IdempotencyStore {

    /**
     * Atomically claims {@code key} within {@code scope} for a request with the given fingerprint.
     *
     * @return the pre-existing record if the key was already claimed, otherwise empty (the key is now ours)
     */
    Optional<IdempotencyRecord> claim(String scope, String key, String fingerprint);

    void complete(String scope, String key, IdempotencyRecord record);

    /** Drops a claim so a later retry can run the operation again (used when the operation fails). */
    void release(String scope, String key);
}
