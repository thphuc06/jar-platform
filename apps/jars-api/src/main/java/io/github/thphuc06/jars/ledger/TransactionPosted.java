package io.github.thphuc06.jars.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Published in the transaction that records a money movement (docs/02-architecture/event-catalog.md). Consumers
 * (insights, budgeting from Phase 2) filter on {@code type}: a JAR_TRANSFER moves money but is not spending.
 */
public record TransactionPosted(
        UUID transactionId,
        UUID userId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        UUID jarId,
        @Nullable UUID toJarId,
        @Nullable UUID categoryId,
        Instant occurredAt) {}
