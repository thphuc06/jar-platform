package io.github.thphuc06.jars.ledger;

import io.github.thphuc06.jars.platform.idempotency.IdempotencyService.Result;
import io.github.thphuc06.jars.shared.UserId;
import java.util.List;

/** The ledger as seen by other modules. Money movements go through the ledger only; nobody else writes its tables. */
public interface LedgerApi {

    /** The user's active (not archived) jars, oldest first. */
    List<JarSummary> jars(UserId userId);

    /** System categories plus the user's own. */
    List<CategorySummary> categories(UserId userId);

    /**
     * Records an income and divides it over jars (one {@code INCOME} transaction plus one {@code ALLOCATE} entry per
     * split) in a single transaction, with the same guarantees as {@code POST /transactions}: retried on concurrent
     * writes, idempotent per {@code (user, idempotencyKey)}, and announced with {@link TransactionPosted}.
     *
     * @param requestHash identifies "the same request" for the key (see {@code IdempotencyService.hash}); reusing the
     *     key with a different hash fails with 422
     * @throws io.github.thphuc06.jars.shared.DomainException 404 for a jar that is not the caller's, 422 when the
     *     amount is not positive, a split is not positive, a jar appears twice, or the splits do not add up to the
     *     amount
     */
    Result<AllocationReceipt> allocateIncome(
            UserId userId, String idempotencyKey, String requestHash, IncomeAllocation allocation);
}
