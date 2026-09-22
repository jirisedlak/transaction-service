package com.assessment.transactions.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Repository;

/** Persistent (for the lifetime of the JVM) projection storage backed by a concurrent in-memory map. */
@Repository
public class InMemoryTransactionRepository implements TransactionRepository {

    private final Map<UUID, Transaction> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Transaction> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Transaction> findAll() {
        return List.copyOf(store.values());
    }

    @Override
    public Optional<Transaction> compute(UUID id, UnaryOperator<Transaction> update) {
        return Optional.ofNullable(store.compute(id, (k, current) -> update.apply(current)));
    }
}
