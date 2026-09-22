package com.assessment.transactions.reconciliation;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.Transaction;
import com.assessment.transactions.domain.TransactionEvent;
import com.assessment.transactions.domain.TransactionRepository;
import com.assessment.transactions.eventsourcing.EventStore;
import com.assessment.transactions.reconciliation.ReconciliationReport.DuplicateEvent;
import com.assessment.transactions.reconciliation.ReconciliationReport.MissingTransition;
import com.assessment.transactions.reconciliation.ReconciliationReport.StaleTransaction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scans all transactions and their events and reports three kinds of anomalies:
 * <ol>
 *   <li><b>stale</b>: not in a terminal state (see {@link com.assessment.transactions.domain.TransactionStatus#isTerminal})
 *       after {@code staleAfter}</li>
 *   <li><b>duplicate events</b>: the same event type recorded more than once on a transaction</li>
 *   <li><b>missing transitions</b>: an event was recorded but its expected successor was not
 *       (see {@link EventType#expectedNext}: {@code CREATED} without {@code APPROVED}, {@code APPROVED}
 *       without {@code SUBMITTED}, {@code RESERVED} without {@code SETTLED}), and the transaction was not reversed</li>
 * </ol>
 * Stale and missing-transition checks only consider transactions older than {@code staleAfter},
 * so in-flight transactions are not reported. Duplicates are reported regardless of age.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final TransactionRepository transactions;
    private final EventStore events;
    private final ReconciliationProperties properties;
    private final Clock clock;

    public ReconciliationService(TransactionRepository transactions, EventStore events,
                                 ReconciliationProperties properties, Clock clock) {
        this.transactions = transactions;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    public ReconciliationReport report() {
        return report(clock.instant());
    }

    /** Builds the report as if the current time were {@code asOf} (lets callers simulate elapsed time). */
    public ReconciliationReport report(Instant asOf) {
        Duration staleAfter = properties.staleAfter();
        List<StaleTransaction> stale = new ArrayList<>();
        List<DuplicateEvent> duplicates = new ArrayList<>();
        List<MissingTransition> missing = new ArrayList<>();

        List<Transaction> all = new ArrayList<>(transactions.findAll());
        all.sort(Comparator.comparing(Transaction::createdAt).thenComparing(t -> t.id().toString()));

        for (Transaction tx : all) {
            Map<EventType, Integer> counts = countByType(events.stream(tx.id()));
            Duration age = Duration.between(tx.createdAt(), asOf);
            boolean windowElapsed = age.compareTo(staleAfter) >= 0;

            if (windowElapsed && !tx.status().isTerminal()) {
                stale.add(new StaleTransaction(tx.id(), tx.status(), tx.createdAt(), age));
            }

            counts.forEach((type, n) -> {
                if (n > 1) {
                    duplicates.add(new DuplicateEvent(tx.id(), type, n));
                }
            });

            if (windowElapsed && !counts.containsKey(EventType.REVERSED)) {
                for (EventType recorded : counts.keySet()) {
                    recorded.expectedNext()
                            .filter(next -> !counts.containsKey(next))
                            .ifPresent(next -> missing.add(new MissingTransition(tx.id(), tx.status(), recorded, next)));
                }
            }
        }

        log.info("Reconciliation as of {} over {} transaction(s): {} stale, {} duplicate event(s), {} missing transition(s)",
                asOf, all.size(), stale.size(), duplicates.size(), missing.size());
        return new ReconciliationReport(asOf, staleAfter, List.copyOf(stale), List.copyOf(duplicates), List.copyOf(missing));
    }

    private static Map<EventType, Integer> countByType(List<TransactionEvent> txEvents) {
        Map<EventType, Integer> counts = new EnumMap<>(EventType.class);
        for (TransactionEvent e : txEvents) {
            counts.merge(e.type(), 1, Integer::sum);
        }
        return counts;
    }
}
