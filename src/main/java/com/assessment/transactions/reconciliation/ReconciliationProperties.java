package com.assessment.transactions.reconciliation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param staleAfter how long a transaction may stay non-terminal before it is reported
 */
@ConfigurationProperties(prefix = "reconciliation")
public record ReconciliationProperties(@DefaultValue("2m") Duration staleAfter) {
}
