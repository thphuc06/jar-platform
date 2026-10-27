package io.github.thphuc06.jars.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Published when a transaction is moved to another category and/or jar (PATCH /transactions/{id}). Spending read
 * models (insights, budgeting) subtract it from the old bucket and add it to the new one. Not published for note-only
 * edits, which change no number.
 */
public record TransactionRecategorized(
        UUID transactionId,
        UUID userId,
        TransactionType type,
        BigDecimal amount,
        @Nullable UUID oldCategoryId,
        @Nullable UUID newCategoryId,
        UUID oldJarId,
        UUID newJarId,
        Instant occurredAt) {}
