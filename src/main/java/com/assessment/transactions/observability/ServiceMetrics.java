package com.assessment.transactions.observability;

import com.assessment.transactions.domain.EventType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The service's own meters, named in one place. Everything is also exposed through
 * {@code /actuator/metrics} and {@code /actuator/prometheus}.
 *
 * <ul>
 *   <li>{@code transaction.events.appended} (counter, tag {@code type}) – events written to the store</li>
 *   <li>{@code idempotency.requests} (counter, tag {@code scope}, {@code outcome}) – executed / replayed / key_reuse / in_progress / failed</li>
 *   <li>{@code events.consumer.deliveries} (counter, tag {@code outcome}) – success / retry / dead_letter</li>
 *   <li>{@code events.projection} (timer, tag {@code outcome}) – time to fold pending events into the read model</li>
 *   <li>{@code events.projection.applied} (counter) – events applied to read models</li>
 *   <li>gauges registered by {@link ConsumerGauges}: queue depth, dead letters, consumer lag</li>
 * </ul>
 */
@Component
public class ServiceMetrics {

    public static final String EVENTS_APPENDED = "transaction.events.appended";
    public static final String IDEMPOTENCY_REQUESTS = "idempotency.requests";
    public static final String CONSUMER_DELIVERIES = "events.consumer.deliveries";
    public static final String PROJECTION_TIMER = "events.projection";
    public static final String PROJECTION_APPLIED = "events.projection.applied";

    private final MeterRegistry registry;

    public ServiceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** For unit tests: a throw-away in-memory registry. */
    public static ServiceMetrics inMemory() {
        return new ServiceMetrics(new SimpleMeterRegistry());
    }

    public MeterRegistry registry() {
        return registry;
    }

    public void eventAppended(EventType type) {
        Counter.builder(EVENTS_APPENDED).description("Events appended to transaction streams")
                .tag("type", type.name()).register(registry).increment();
    }

    public void idempotency(String scope, String outcome) {
        Counter.builder(IDEMPOTENCY_REQUESTS).description("Idempotent requests by outcome")
                .tag("scope", scope).tag("outcome", outcome).register(registry).increment();
    }

    public void delivery(String outcome) {
        Counter.builder(CONSUMER_DELIVERIES).description("Event deliveries to the consumer by outcome")
                .tag("outcome", outcome).register(registry).increment();
    }

    public void projectionApplied(int events) {
        Counter.builder(PROJECTION_APPLIED).description("Events applied to transaction read models")
                .register(registry).increment(events);
    }

    /** Times a projection and tags it with the outcome. */
    public <T> T timeProjection(Supplier<T> projection) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return projection.get();
        } catch (RuntimeException e) {
            outcome = "failure";
            throw e;
        } finally {
            sample.stop(Timer.builder(PROJECTION_TIMER).description("Time to fold pending events into a read model")
                    .tag("outcome", outcome).register(registry));
        }
    }

    public void recordProjection(Duration duration, String outcome) {
        Timer.builder(PROJECTION_TIMER).tag("outcome", outcome).register(registry).record(duration);
    }
}
