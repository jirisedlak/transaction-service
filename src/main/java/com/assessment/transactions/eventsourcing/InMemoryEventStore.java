package com.assessment.transactions.eventsourcing;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.domain.TransactionNotFoundException;
import com.assessment.transactions.observability.ServiceMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Persistent (for the lifetime of the JVM) event streams backed by a concurrent in-memory map. */
@Component
public class InMemoryEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventStore.class);

    private final Map<UUID, List<TransactionEvent>> streams = new ConcurrentHashMap<>();
    private final ServiceMetrics metrics;

    @Autowired
    public InMemoryEventStore(ServiceMetrics metrics) {
        this.metrics = metrics;
    }

    /** For tests: throw-away metrics. */
    public InMemoryEventStore() {
        this(ServiceMetrics.inMemory());
    }

    @Override
    public TransactionEvent append(UUID transactionId, EventType type, Map<String, Object> payload,
                                   Instant occurredAt, Instant recordedAt, String correlationId) {
        TransactionEvent[] appended = new TransactionEvent[1];
        // compute() serializes appends per transaction, so sequence numbers are contiguous and ordered
        streams.compute(transactionId, (id, stream) -> {
            if (stream == null) {
                if (type != EventType.CREATED) {
                    throw new TransactionNotFoundException(id);
                }
                stream = new CopyOnWriteArrayList<>();
            } else if (type == EventType.CREATED) {
                throw new IllegalStateException("Transaction " + id + " already has a stream; CREATED must be its first event");
            }
            long sequence = stream.size() + 1L;
            appended[0] = new TransactionEvent(UUID.randomUUID(), id, sequence, type, Map.copyOf(payload), occurredAt, recordedAt, correlationId);
            stream.add(appended[0]);
            return stream;
        });
        metrics.eventAppended(type);
        log.debug("Appended {} as sequence {} to stream of transaction {}", type, appended[0].sequence(), transactionId);
        return appended[0];
    }

    @Override
    public List<TransactionEvent> stream(UUID transactionId) {
        return List.copyOf(streams.getOrDefault(transactionId, List.of()));
    }

    @Override
    public List<TransactionEvent> streamAfter(UUID transactionId, long afterSequence) {
        return streams.getOrDefault(transactionId, List.of()).stream()
                .filter(e -> e.sequence() > afterSequence)
                .toList();
    }

    @Override
    public Set<UUID> transactionIds() {
        return Set.copyOf(streams.keySet());
    }
}
