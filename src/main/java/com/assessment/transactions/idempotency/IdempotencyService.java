package com.assessment.transactions.idempotency;

import com.assessment.transactions.logging.TraceContext;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    public static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyStore store;
    private final RequestFingerprinter fingerprinter;

    public IdempotencyService(IdempotencyStore store, RequestFingerprinter fingerprinter) {
        this.store = store;
        this.fingerprinter = fingerprinter;
    }

    public <T> IdempotentResult<T> execute(String scope, String key, Object request, Supplier<IdempotentResult<T>> operation) {
        validateKey(key);
        try (TraceContext.Scope ignored = TraceContext.with(TraceContext.IDEMPOTENCY_KEY, key)) {
            String fingerprint = fingerprinter.fingerprint(request);

            Optional<IdempotencyRecord> existing = store.claim(scope, key, fingerprint);
            if (existing.isPresent()) {
                return replay(scope, key, fingerprint, existing.get());
            }

            log.debug("First request in scope '{}', executing", scope);
            try {
                IdempotentResult<T> result = operation.get();
                store.complete(scope, key, IdempotencyRecord.inProgress(fingerprint).completed(result.status(), result.body()));
                return result;
            } catch (RuntimeException e) {
                log.warn("Operation failed in scope '{}', releasing key for retry: {}", scope, e.toString());
                store.release(scope, key);
                throw e;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> IdempotentResult<T> replay(String scope, String key, String fingerprint, IdempotencyRecord record) {
        if (!record.fingerprint().equals(fingerprint)) {
            log.warn("Key reused in scope '{}' with a different payload", scope);
            throw new IdempotencyException.KeyReuse(key);
        }
        if (record.isInProgress()) {
            log.warn("Duplicate request in scope '{}' while the original is still in progress", scope);
            throw new IdempotencyException.InProgress(key);
        }
        log.info("Replaying stored {} response in scope '{}'", record.status(), scope);
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
