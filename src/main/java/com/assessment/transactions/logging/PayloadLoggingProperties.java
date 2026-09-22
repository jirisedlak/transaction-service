package com.assessment.transactions.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled   whether request/response bodies are logged at all
 * @param maxLength bodies longer than this are truncated in the log (characters)
 */
@ConfigurationProperties(prefix = "http.payload-logging")
public record PayloadLoggingProperties(@DefaultValue("true") boolean enabled, @DefaultValue("2048") int maxLength) {
}
