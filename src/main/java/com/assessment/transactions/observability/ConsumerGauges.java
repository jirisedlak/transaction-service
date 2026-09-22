package com.assessment.transactions.observability;

import com.assessment.transactions.domain.Transaction;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.domain.TransactionRepository;
import com.assessment.transactions.eventsourcing.DeadLetterStore;
import com.assessment.transactions.eventsourcing.EventBus;
import com.assessment.transactions.eventsourcing.EventStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Gauges describing the asynchronous path:
 * <ul>
 *   <li>{@code events.consumer.queue.depth} – events published but not yet picked up</li>
 *   <li>{@code events.dead_letters} – parked events</li>
 *   <li>{@code events.consumer.lag.max} – largest gap (latest stream sequence − read-model version) over all transactions</li>
 *   <li>{@code events.consumer.lag.transactions} – number of transactions whose read model is behind their stream</li>
 * </ul>
 * Lag is computed on scrape by walking all streams; fine for the in-memory stores, a query in the database variant.
 */
@Component
public class ConsumerGauges {

    public static final String QUEUE_DEPTH = "events.consumer.queue.depth";
    public static final String DEAD_LETTERS = "events.dead_letters";
    public static final String LAG_MAX = "events.consumer.lag.max";
    public static final String LAG_TRANSACTIONS = "events.consumer.lag.transactions";

    private final EventStore eventStore;
    private final TransactionRepository repository;

    public ConsumerGauges(MeterRegistry registry, EventBus bus, DeadLetterStore deadLetters,
                          EventStore eventStore, TransactionRepository repository) {
        this.eventStore = eventStore;
        this.repository = repository;
        Gauge.builder(QUEUE_DEPTH, bus, EventBus::queueDepth).description("Events waiting for the consumer").register(registry);
        Gauge.builder(DEAD_LETTERS, deadLetters, d -> d.findAll().size()).description("Dead-lettered events").register(registry);
        Gauge.builder(LAG_MAX, this, g -> g.lag().max()).description("Largest stream-vs-read-model gap").register(registry);
        Gauge.builder(LAG_TRANSACTIONS, this, g -> g.lag().transactionsBehind()).description("Transactions whose read model is behind").register(registry);
    }

    public record Lag(long max, long transactionsBehind) {
    }

    public Lag lag() {
        long max = 0;
        long behind = 0;
        for (UUID id : eventStore.transactionIds()) {
            List<TransactionEvent> stream = eventStore.stream(id);
            if (stream.isEmpty()) {
                continue;
            }
            long latest = stream.get(stream.size() - 1).sequence();
            long version = repository.findById(id).map(Transaction::version).orElse(0L);
            long gap = latest - version;
            if (gap > 0) {
                behind++;
                max = Math.max(max, gap);
            }
        }
        return new Lag(max, behind);
    }
}
