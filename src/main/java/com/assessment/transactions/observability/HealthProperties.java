package com.assessment.transactions.observability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param pingTimeout how long the health check waits for the consumer thread to answer a no-op */
@ConfigurationProperties(prefix = "health.consumer")
public record HealthProperties(@DefaultValue("2s") Duration pingTimeout) {
}
