package io.github.thphuc06.jars.ledger;

import io.github.thphuc06.jars.shared.UserId;
import java.util.UUID;

/** The category and/or jar of an existing transaction changed. Old and new ids are equal when that part did not change. */
public record TransactionRecategorized(
        UUID transactionId, UserId userId, UUID oldCategoryId, UUID newCategoryId, UUID oldJarId, UUID newJarId) {}
