package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.ledger.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read-only copy of a recorded transaction for the web layer. */
public record TransactionView(
        UUID id,
        TransactionType type,
        BigDecimal amount,
        String currency,
        UUID jarId,
        @Nullable UUID toJarId,
        @Nullable UUID categoryId,
        @Nullable String counterparty,
        @Nullable String note,
        Instant occurredAt) {

    static TransactionView of(LedgerTransaction t) {
        return new TransactionView(
                t.id(),
                t.type(),
                t.amount(),
                t.currency(),
                t.jarId(),
                t.toJarId(),
                t.categoryId(),
                t.counterparty(),
                t.note(),
                t.occurredAt());
    }
}
