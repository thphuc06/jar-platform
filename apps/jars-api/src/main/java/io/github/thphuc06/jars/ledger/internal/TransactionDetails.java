package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.ledger.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A transaction as listed in the Money Inbox: with the names of its jar(s) and category, so the UI needs no lookups. */
public record TransactionDetails(
        UUID id,
        TransactionType type,
        BigDecimal amount,
        String currency,
        JarRef jar,
        @Nullable JarRef toJar,
        @Nullable CategoryRef category,
        @Nullable String counterparty,
        @Nullable String note,
        Instant occurredAt) {

    public record JarRef(UUID id, JarCode code, String name) {

        static @Nullable JarRef of(@Nullable Jar jar) {
            return jar == null ? null : new JarRef(jar.id(), jar.code(), jar.name());
        }
    }

    public record CategoryRef(UUID id, String name) {

        static @Nullable CategoryRef of(@Nullable Category category) {
            return category == null ? null : new CategoryRef(category.id(), category.name());
        }
    }

    /** Reads the associations: they must have been fetched (entity graph) or the persistence context must be open. */
    static TransactionDetails of(LedgerTransaction t) {
        return new TransactionDetails(
                t.id(),
                t.type(),
                t.amount(),
                t.currency(),
                JarRef.of(t.jar()),
                JarRef.of(t.toJar()),
                CategoryRef.of(t.category()),
                t.counterparty(),
                t.note(),
                t.occurredAt());
    }
}
