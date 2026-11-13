package io.github.thphuc06.jars.ledger;

import io.github.thphuc06.jars.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of an imported bank statement, as the ledger records it: an income (optionally into one jar) or an expense
 * from one jar. The row's line in the file never reaches the ledger; the importer turns it into the idempotency key.
 *
 * @param income true for money in, false for money out
 * @param amount positive
 * @param jarId the jar of an expense (required) or of an income (null leaves the income unallocated)
 * @param categoryId a category of the matching kind (system or the user's own)
 */
public record ImportedTransaction(
        boolean income, Money amount, UUID jarId, UUID categoryId, String counterparty, Instant occurredAt) {

    /** What the ledger did with a row: the transaction, and whether this key had already been recorded before. */
    public record Recorded(UUID transactionId, boolean replayed) {}
}
