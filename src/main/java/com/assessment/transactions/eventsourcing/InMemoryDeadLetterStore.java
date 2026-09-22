package com.assessment.transactions.eventsourcing;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryDeadLetterStore implements DeadLetterStore {

    private final Map<UUID, DeadLetter> parked = new ConcurrentHashMap<>();

    @Override
    public void put(DeadLetter deadLetter) {
        parked.put(deadLetter.event().id(), deadLetter);
    }

    @Override
    public Optional<DeadLetter> find(UUID eventId) {
        return Optional.ofNullable(parked.get(eventId));
    }

    @Override
    public Optional<DeadLetter> remove(UUID eventId) {
        return Optional.ofNullable(parked.remove(eventId));
    }

    @Override
    public List<DeadLetter> findAll() {
        return parked.values().stream()
                .sorted(Comparator.comparing(DeadLetter::failedAt).thenComparing(d -> d.event().id().toString()))
                .toList();
    }
}
