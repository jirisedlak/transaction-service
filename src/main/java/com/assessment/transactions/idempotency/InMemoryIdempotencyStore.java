package com.assessment.transactions.idempotency;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> claim(String scope, String key, String fingerprint) {
        return Optional.ofNullable(records.putIfAbsent(compositeKey(scope, key), IdempotencyRecord.inProgress(fingerprint)));
    }

    @Override
    public void complete(String scope, String key, IdempotencyRecord record) {
        records.put(compositeKey(scope, key), record);
    }

    @Override
    public void release(String scope, String key) {
        records.remove(compositeKey(scope, key));
    }

    private static String compositeKey(String scope, String key) {
        return scope + ":" + key;
    }
}
