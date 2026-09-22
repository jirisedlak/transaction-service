package com.assessment.transactions.observability;

import com.assessment.transactions.eventsourcing.DeadLetterStore;
import com.assessment.transactions.eventsourcing.EventBus;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Health of the asynchronous path, reported as component {@code eventConsumer} of {@code /actuator/health}.
 *
 * <ul>
 *   <li>{@code DOWN} – the consumer is no longer running (executor shut down)</li>
 *   <li>{@code DEGRADED} – the consumer did not answer a ping within {@code health.consumer.ping-timeout}
 *       (queue stuck or very deep), or there are dead-lettered events</li>
 *   <li>{@code UP} – otherwise</li>
 * </ul>
 * {@code DEGRADED} maps to HTTP 200 (see application.yml) so a container health check keeps the
 * instance alive – restarting would not fix a poison event and would lose in-memory state –
 * while the detail is visible to operators and alerting. Details always include queue depth,
 * dead-letter count and consumer lag.
 */
@Component
public class EventConsumerHealthIndicator implements HealthIndicator {

    public static final Status DEGRADED = new Status("DEGRADED");

    private final EventBus bus;
    private final DeadLetterStore deadLetters;
    private final ConsumerGauges gauges;
    private final HealthProperties properties;

    public EventConsumerHealthIndicator(EventBus bus, DeadLetterStore deadLetters, ConsumerGauges gauges, HealthProperties properties) {
        this.bus = bus;
        this.deadLetters = deadLetters;
        this.gauges = gauges;
        this.properties = properties;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up();
        if (!bus.isRunning()) {
            health.status(Status.DOWN).withDetail("reason", "consumer is not running");
        } else if (!respondsWithin(properties.pingTimeout())) {
            health.status(DEGRADED).withDetail("reason", "consumer did not respond within " + properties.pingTimeout());
        }

        int parked = deadLetters.findAll().size();
        if (parked > 0 && !Status.DOWN.equals(health.build().getStatus())) {
            health.status(DEGRADED).withDetail("reason", parked + " dead-lettered event(s)");
        }

        ConsumerGauges.Lag lag = gauges.lag();
        return health
                .withDetail("queueDepth", bus.queueDepth())
                .withDetail("deadLetters", parked)
                .withDetail("lagMax", lag.max())
                .withDetail("lagTransactions", lag.transactionsBehind())
                .build();
    }

    private boolean respondsWithin(Duration timeout) {
        try {
            bus.ping().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
