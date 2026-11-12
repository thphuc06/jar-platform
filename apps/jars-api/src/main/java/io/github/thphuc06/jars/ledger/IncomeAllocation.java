package io.github.thphuc06.jars.ledger;

import io.github.thphuc06.jars.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An income that is divided over several jars in one step. The ledger records one {@code INCOME} transaction (not tied
 * to a single jar) and one {@code ALLOCATE} entry per split, all in the same database transaction.
 *
 * <p>The split amounts must add up to {@code amount} exactly. Rounding a percentage split to two decimals (the
 * largest-remainder method) is the caller's job; the ledger only checks the sum and rejects a mismatch with 422.
 *
 * @param categoryId an INCOME category, or null
 * @param occurredAt when the income happened; null means now
 */
public record IncomeAllocation(
        Money amount, List<Split> splits, UUID categoryId, String counterparty, String note, Instant occurredAt) {

    /** The part of the income that goes into one jar. */
    public record Split(UUID jarId, Money amount) {}
}
