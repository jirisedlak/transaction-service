package com.assessment.transactions.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.assessment.transactions.domain.EventType;
import com.assessment.transactions.domain.InMemoryTransactionRepository;
import com.assessment.transactions.domain.TransactionStatus;
import com.assessment.transactions.eventsourcing.InMemoryEventBus;
import com.assessment.transactions.eventsourcing.InMemoryEventStore;
import com.assessment.transactions.eventsourcing.TransactionProjector;
import com.assessment.transactions.reconciliation.ReconciliationReport.DuplicateEvent;
import com.assessment.transactions.reconciliation.ReconciliationReport.MissingTransition;
import com.assessment.transactions.reconciliation.ReconciliationReport.StaleTransaction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T10:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    private final InMemoryTransactionRepository transactions = new InMemoryTransactionRepository();
    private final InMemoryEventStore events = new InMemoryEventStore();
    private final TransactionProjector projector = new TransactionProjector(events, transactions, new InMemoryEventBus());
    private final ReconciliationService service = new ReconciliationService(
            transactions, events, new ReconciliationProperties(STALE_AFTER), Clock.fixed(T0, ZoneOffset.UTC));

    /** Creates a transaction at T0, appends the given events one second apart, and projects it. */
    private UUID transaction(EventType... eventTypes) {
        UUID id = UUID.randomUUID();
        events.append(id, EventType.CREATED, Map.of("accountId", "acc", "amount", 10, "currency", "EUR"), T0, T0);
        for (int i = 0; i < eventTypes.length; i++) {
            Instant at = T0.plusSeconds(i + 1);
            events.append(id, eventTypes[i], Map.of(), at, at);
        }
        projector.project(id);
        return id;
    }

    @Test
    void emptyStoreYieldsEmptyReport() {
        ReconciliationReport report = service.report();
        assertThat(report.asOf()).isEqualTo(T0);
        assertThat(report.staleAfter()).isEqualTo(STALE_AFTER);
        assertThat(report.findingCount()).isZero();
    }

    @Test
    void reportsNonTerminalTransactionsOnlyAfterStaleWindow() {
        UUID untouched = transaction();
        UUID reserved = transaction(EventType.APPROVED, EventType.SUBMITTED, EventType.RESERVED);
        UUID approved = transaction(EventType.APPROVED);
        UUID submitted = transaction(EventType.APPROVED, EventType.SUBMITTED);
        UUID settled = transaction(EventType.APPROVED, EventType.SUBMITTED, EventType.RESERVED, EventType.SETTLED);
        UUID reversed = transaction(EventType.APPROVED, EventType.REVERSED);

        assertThat(service.report(T0.plus(STALE_AFTER).minusSeconds(1)).staleTransactions()).isEmpty();

        ReconciliationReport report = service.report(T0.plus(STALE_AFTER));
        assertThat(report.staleTransactions())
                .extracting(StaleTransaction::transactionId, StaleTransaction::status, StaleTransaction::age)
                .containsExactlyInAnyOrder(
                        tuple(untouched, TransactionStatus.NEW, STALE_AFTER),
                        tuple(reserved, TransactionStatus.RESERVED, STALE_AFTER));
        assertThat(report.staleTransactions()).extracting(StaleTransaction::transactionId)
                .doesNotContain(approved, submitted, settled, reversed);
    }

    @Test
    void reportsEventTypesRecordedMoreThanOnceRegardlessOfAge() {
        UUID duplicated = transaction(EventType.APPROVED, EventType.SUBMITTED, EventType.SUBMITTED, EventType.SUBMITTED, EventType.APPROVED);
        transaction(EventType.APPROVED, EventType.SUBMITTED);

        ReconciliationReport report = service.report(T0.plusSeconds(10));
        assertThat(report.duplicateEvents())
                .extracting(DuplicateEvent::transactionId, DuplicateEvent::eventType, DuplicateEvent::occurrences)
                .containsExactlyInAnyOrder(
                        tuple(duplicated, EventType.APPROVED, 2),
                        tuple(duplicated, EventType.SUBMITTED, 3));
    }

    @Test
    void reportsMissingExpectedTransitionsAfterStaleWindow() {
        UUID neverApproved = transaction();
        UUID approvedOnly = transaction(EventType.APPROVED);
        UUID reservedOnly = transaction(EventType.APPROVED, EventType.SUBMITTED, EventType.RESERVED);
        UUID skippedApproval = transaction(EventType.SUBMITTED);
        transaction(EventType.APPROVED, EventType.SUBMITTED);
        transaction(EventType.APPROVED, EventType.SUBMITTED, EventType.RESERVED, EventType.SETTLED);
        transaction(EventType.REVERSED); // reversal legitimately ends the chain

        assertThat(service.report(T0.plusSeconds(30)).missingTransitions()).isEmpty();

        ReconciliationReport report = service.report(T0.plus(STALE_AFTER));
        assertThat(report.missingTransitions())
                .extracting(MissingTransition::transactionId, MissingTransition::recorded, MissingTransition::expectedNext)
                .containsExactlyInAnyOrder(
                        tuple(neverApproved, EventType.CREATED, EventType.APPROVED),
                        tuple(approvedOnly, EventType.APPROVED, EventType.SUBMITTED),
                        tuple(reservedOnly, EventType.RESERVED, EventType.SETTLED),
                        tuple(skippedApproval, EventType.CREATED, EventType.APPROVED));
    }
}
