package com.assessment.transactions.eventsourcing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxAttempts  delivery attempts per event before it is dead-lettered (>= 1)
 * @param retryBackoff pause between attempts
 * @param drainTimeout on shutdown, how long to keep consuming already-published events before giving up
 */
@ConfigurationProperties(prefix = "events.consumer")
public record ConsumerProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("100ms") Duration retryBackoff,
        @DefaultValue("10s") Duration drainTimeout) {
}
