package io.github.thphuc06.jars.ledger;

import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Instant;
import java.util.UUID;

/**
 * Money was recorded (income, expense or jar transfer). Published inside the posting transaction, so listeners that
 * use {@code @TransactionalEventListener}/{@code @ApplicationModuleListener} only see it after a commit.
 *
 * @param type {@code INCOME}, {@code EXPENSE} or {@code JAR_TRANSFER}
 * @param jarId the jar spent from or credited; null for income that is not assigned to a jar
 */
public record TransactionPosted(
        UUID transactionId,
        UserId userId,
        String type,
        Money amount,
        UUID jarId,
        UUID categoryId,
        Instant occurredAt,
        String source) {}
