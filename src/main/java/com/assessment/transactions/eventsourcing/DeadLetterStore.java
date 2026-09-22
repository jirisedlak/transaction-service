package com.assessment.transactions.eventsourcing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Dead-letter queue for events whose consumption failed permanently. Keyed by event id. */
public interface DeadLetterStore {

    void put(DeadLetter deadLetter);

    Optional<DeadLetter> find(UUID eventId);

    Optional<DeadLetter> remove(UUID eventId);

    /** All parked events, oldest failure first. */
    List<DeadLetter> findAll();
}
