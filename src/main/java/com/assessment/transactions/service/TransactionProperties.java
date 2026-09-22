package com.assessment.transactions.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param projectionTimeout how long {@code POST /transactions} waits for the consumer to project the
 *                          {@code CREATED} event before answering from the event itself
 */
@ConfigurationProperties(prefix = "transactions")
public record TransactionProperties(@DefaultValue("5s") Duration projectionTimeout) {
}
