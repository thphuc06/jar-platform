package io.github.thphuc06.jars.ledger;

import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Instant;
import java.util.UUID;

/**
 * The category and/or jar of an existing transaction changed. Old and new ids are equal when that part did not
 * change. The amount and date are included so a read model can move the spend without asking the ledger.
 *
 * @param eventId unique per publication (UUIDv7)
 * @param type type of the transaction ({@code EXPENSE} or {@code INCOME})
 */
public record TransactionRecategorized(
        UUID eventId,
        UUID transactionId,
        UserId userId,
        String type,
        Money amount,
        Instant occurredAt,
        UUID oldCategoryId,
        UUID newCategoryId,
        UUID oldJarId,
        UUID newJarId) {}
