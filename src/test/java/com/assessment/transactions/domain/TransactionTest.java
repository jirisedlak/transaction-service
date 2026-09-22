package com.assessment.transactions.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private static final Instant T0 = Instant.parse("2026-09-22T10:00:00Z");
    private final UUID txId = UUID.randomUUID();

    private TransactionEvent event(long seq, EventType type) {
        Map<String, Object> payload = type == EventType.CREATED
                ? Map.of("accountId", "acc", "amount", new BigDecimal("12.50"), "currency", "EUR", "reference", "ref")
                : Map.of();
        return new TransactionEvent(UUID.randomUUID(), txId, seq, type, payload, T0.plusSeconds(seq), T0.plusSeconds(seq));
    }

    @Test
    void replayFoldsStreamIntoState() {
        Transaction tx = Transaction.replay(List.of(
                event(1, EventType.CREATED), event(2, EventType.APPROVED), event(3, EventType.SUBMITTED))).orElseThrow();

        assertThat(tx.id()).isEqualTo(txId);
        assertThat(tx.accountId()).isEqualTo("acc");
        assertThat(tx.amount()).isEqualByComparingTo("12.50");
        assertThat(tx.currency()).isEqualTo("EUR");
        assertThat(tx.reference()).isEqualTo("ref");
        assertThat(tx.status()).isEqualTo(TransactionStatus.SUBMITTED);
        assertThat(tx.createdAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(tx.updatedAt()).isEqualTo(T0.plusSeconds(3));
        assertThat(tx.version()).isEqualTo(3);
    }

    @Test
    void replayOfEmptyStreamIsEmpty() {
        assertThat(Transaction.replay(List.of())).isEmpty();
    }

    @Test
    void applyingAlreadySeenEventIsNoOp() {
        Transaction tx = Transaction.from(event(1, EventType.CREATED)).apply(event(2, EventType.APPROVED));
        assertThat(tx.apply(event(2, EventType.SETTLED))).isSameAs(tx);
        assertThat(tx.apply(event(1, EventType.CREATED))).isSameAs(tx);
    }

    @Test
    void rejectsGapsInSequence() {
        Transaction tx = Transaction.from(event(1, EventType.CREATED));
        assertThatThrownBy(() -> tx.apply(event(3, EventType.APPROVED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected sequence 2");
    }

    @Test
    void streamMustStartWithCreated() {
        assertThatThrownBy(() -> Transaction.from(event(1, EventType.APPROVED)))
                .isInstanceOf(IllegalStateException.class);
        Transaction tx = Transaction.from(event(1, EventType.CREATED));
        assertThatThrownBy(() -> tx.apply(event(2, EventType.CREATED)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void terminalStatuses() {
        assertThat(TransactionStatus.values()).filteredOn(TransactionStatus::isTerminal)
                .containsExactlyInAnyOrder(TransactionStatus.SUBMITTED, TransactionStatus.APPROVED,
                        TransactionStatus.SETTLED, TransactionStatus.REVERSED);
    }
}
