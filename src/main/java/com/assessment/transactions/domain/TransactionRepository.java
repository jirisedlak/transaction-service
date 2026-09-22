package com.assessment.transactions.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Persistent read model (projection) of transactions. The event stream is the source of truth. */
public interface TransactionRepository {

    Optional<Transaction> findById(UUID id);

    List<Transaction> findAll();

    /**
     * Atomically replaces the stored transaction with {@code update.apply(current)}, where
     * {@code current} is {@code null} when no projection exists yet. Returning {@code null}
     * removes the projection. Concurrent updates of the same id are serialized.
     */
    Optional<Transaction> compute(UUID id, UnaryOperator<Transaction> update);
}
