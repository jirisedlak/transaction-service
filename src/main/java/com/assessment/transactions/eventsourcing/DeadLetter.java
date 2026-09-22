package com.assessment.transactions.eventsourcing;

import com.assessment.transactions.domain.TransactionEvent;
import java.time.Instant;

/**
 * An event the consumer could not process after all retries, parked for inspection and redelivery.
 *
 * @param error    last failure, as {@code Throwable.toString()}
 * @param attempts number of delivery attempts made before parking
 */
public record DeadLetter(TransactionEvent event, String error, int attempts, Instant failedAt) {
}
