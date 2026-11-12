package io.github.thphuc06.jars.ledger;

import io.github.thphuc06.jars.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What {@link LedgerApi#allocateIncome} did: the income transaction and what every jar received. */
public record AllocationReceipt(UUID transactionId, Money amount, Instant occurredAt, List<Credit> credits) {

    /** One jar that received money, with its new balance. */
    public record Credit(UUID jarId, Money amount, Money balance) {}
}
