package com.assessment.transactions.logging;

import com.assessment.transactions.domain.TransactionEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Tracing identifiers carried in the SLF4J MDC so every log line can be tied back to the HTTP
 * request that caused it, even when the work happens later on the event-consumer thread.
 *
 * <ul>
 *   <li>{@code correlationId}: one per inbound request (from {@value #CORRELATION_HEADER} or generated),
 *       echoed in the response header, stored on every event the request produces</li>
 *   <li>{@code transactionId}, {@code eventId}, {@code sequence}: the domain object being worked on</li>
 *   <li>{@code idempotencyKey}: the client's key for the request</li>
 * </ul>
 */
public final class TraceContext {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    public static final String CORRELATION_ID = "correlationId";
    public static final String TRANSACTION_ID = "transactionId";
    public static final String EVENT_ID = "eventId";
    public static final String SEQUENCE = "sequence";
    public static final String IDEMPOTENCY_KEY = "idempotencyKey";

    private TraceContext() {
    }

    public static String newCorrelationId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** The current request's correlation id, or {@code null} outside a request/consumer scope. */
    public static String correlationId() {
        return MDC.get(CORRELATION_ID);
    }

    /** Puts one value into the MDC until the returned scope is closed. */
    public static Scope with(String key, Object value) {
        return with(Map.of(key, String.valueOf(value)));
    }

    /** Puts several values into the MDC until the returned scope is closed. */
    public static Scope with(Map<String, String> values) {
        Scope scope = new Scope(snapshot());
        values.forEach(MDC::put);
        return scope;
    }

    /** Scope for work on a specific event: its correlation id, transaction, id and sequence. */
    public static Scope forEvent(TransactionEvent event) {
        Map<String, String> values = new HashMap<>();
        if (event.correlationId() != null) {
            values.put(CORRELATION_ID, event.correlationId());
        }
        values.put(TRANSACTION_ID, event.transactionId().toString());
        values.put(EVENT_ID, event.id().toString());
        values.put(SEQUENCE, Long.toString(event.sequence()));
        return with(values);
    }

    /** Copy of the current MDC, for handing to another thread. */
    public static Map<String, String> snapshot() {
        Map<String, String> copy = MDC.getCopyOfContextMap();
        return copy == null ? Map.of() : copy;
    }

    /** Replaces the MDC with a snapshot until the returned scope is closed. */
    public static Scope restore(Map<String, String> snapshot) {
        Scope scope = new Scope(snapshot());
        MDC.setContextMap(new HashMap<>(snapshot));
        return scope;
    }

    /** Restores the MDC as it was before the scope was opened. */
    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previous;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(new HashMap<>(previous));
            }
        }
    }
}
