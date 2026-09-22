package com.assessment.transactions.idempotency;

import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Wraps a side-effecting operation so that repeating it with the same {@code Idempotency-Key}
 * and the same request payload returns the original result instead of running again.
 *
 * <ul>
 *   <li>same key, same payload  -> original response replayed</li>
 *   <li>same key, other payload -> {@link IdempotencyException.KeyReuse} (422)</li>
 *   <li>same key, still running -> {@link IdempotencyException.InProgress} (409)</li>
 *   <li>operation throws        -> claim released so the client may retry</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    public static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyStore store;
    private final RequestFingerprinter fingerprinter;

    public IdempotencyService(IdempotencyStore store, RequestFingerprinter fingerprinter) {
        this.store = store;
        this.fingerprinter = fingerprinter;
    }

    public <T> IdempotentResult<T> execute(String scope, String key, Object request, Supplier<IdempotentResult<T>> operation) {
        validateKey(key);
        String fingerprint = fingerprinter.fingerprint(request);

        Optional<IdempotencyRecord> existing = store.claim(scope, key, fingerprint);
        if (existing.isPresent()) {
            return replay(key, fingerprint, existing.get());
        }

        try {
            IdempotentResult<T> result = operation.get();
            store.complete(scope, key, IdempotencyRecord.inProgress(fingerprint).completed(result.status(), result.body()));
            return result;
        } catch (RuntimeException e) {
            store.release(scope, key);
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> IdempotentResult<T> replay(String key, String fingerprint, IdempotencyRecord record) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new IdempotencyException.KeyReuse(key);
        }
        if (record.isInProgress()) {
            throw new IdempotencyException.InProgress(key);
        }
        return new IdempotentResult<>(record.status(), (T) record.body(), false).asReplay();
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IdempotencyException.InvalidKey("Idempotency-Key header must not be blank");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IdempotencyException.InvalidKey("Idempotency-Key header must be at most " + MAX_KEY_LENGTH + " characters");
        }
    }
}
