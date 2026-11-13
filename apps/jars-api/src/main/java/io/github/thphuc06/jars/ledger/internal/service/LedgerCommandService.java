package io.github.thphuc06.jars.ledger.internal.service;

import io.github.thphuc06.jars.ledger.AllocationReceipt;
import io.github.thphuc06.jars.ledger.IncomeAllocation;
import io.github.thphuc06.jars.ledger.internal.domain.TransactionType;
import io.github.thphuc06.jars.ledger.internal.service.Views.JarView;
import io.github.thphuc06.jars.ledger.internal.service.Views.TransactionReceipt;
import io.github.thphuc06.jars.ledger.internal.service.Views.TransactionView;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService.Result;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything that changes money or jars. Each public posting method has the same shape:
 *
 * <pre>
 * retry (outside)  →  transaction  →  idempotency key  →  business rules  →  entries + balance  →  event
 * </pre>
 *
 * so a request that loses an optimistic-lock race is rolled back completely (including its idempotency key) and run
 * again on fresh data, and a duplicate request returns the stored response instead of posting twice.
 *
 * <p>This class owns the first three steps; the rest is {@link LedgerOperations}, a separate bean so that its methods
 * can be {@code @Audited} (an audit row is written inside the attempt's transaction, never once per retry and never
 * for a replay).
 */
@Service
public class LedgerCommandService {

    public record PostTransactionCommand(
            TransactionType type,
            Money amount,
            UUID jarId,
            UUID categoryId,
            String counterparty,
            String note,
            Instant occurredAt) {}

    public record TransferCommand(UUID fromJarId, UUID toJarId, Money amount, String note) {}

    private final LedgerOperations operations;
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;
    private final OptimisticRetry retry;

    LedgerCommandService(
            LedgerOperations operations,
            IdempotencyService idempotency,
            TransactionTemplate tx,
            LedgerProperties properties) {
        this.operations = operations;
        this.idempotency = idempotency;
        this.tx = tx;
        this.retry = OptimisticRetry.withJitter(properties.maxAttempts());
    }

    // ---------------------------------------------------------------- postings

    public Result<TransactionReceipt> post(
            UserId userId, String idempotencyKey, String requestHash, PostTransactionCommand cmd) {
        return retry.run(() -> tx.execute(status -> idempotency.execute(
                userId,
                idempotencyKey,
                requestHash,
                TransactionReceipt.class,
                201,
                () -> operations.post(userId, cmd))));
    }

    /**
     * One row of an imported bank statement, in the caller's transaction (a Spring Batch chunk): the posting, its
     * idempotency key and the batch's own bookkeeping commit or roll back together. Unlike {@link #post} there is no
     * retry here, because a retry inside a transaction that is not ours cannot start from fresh data; the batch step
     * retries the whole chunk on an optimistic-lock conflict instead.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result<TransactionReceipt> recordImported(
            UserId userId, String idempotencyKey, String requestHash, PostTransactionCommand cmd) {
        return idempotency.execute(
                userId,
                idempotencyKey,
                requestHash,
                TransactionReceipt.class,
                201,
                () -> operations.postImported(userId, cmd));
    }

    public Result<TransactionReceipt> transfer(
            UserId userId, String idempotencyKey, String requestHash, TransferCommand cmd) {
        return retry.run(() -> tx.execute(status -> idempotency.execute(
                userId,
                idempotencyKey,
                requestHash,
                TransactionReceipt.class,
                201,
                () -> operations.transfer(userId, cmd))));
    }

    /**
     * One income divided over several jars: an INCOME transaction without a jar plus one ALLOCATE entry per split, in a
     * single transaction (all of it or nothing), with the same retry, idempotency and event behaviour as {@link #post}.
     */
    public Result<AllocationReceipt> allocateIncome(
            UserId userId, String idempotencyKey, String requestHash, IncomeAllocation cmd) {
        return retry.run(() -> tx.execute(status -> idempotency.execute(
                userId,
                idempotencyKey,
                requestHash,
                AllocationReceipt.class,
                201,
                () -> operations.allocateIncome(userId, cmd))));
    }

    /** Changes the category and/or jar of an existing transaction; a null argument keeps the current value. */
    public TransactionView recategorize(UserId userId, UUID transactionId, UUID categoryId, UUID jarId) {
        return retry.run(() -> tx.execute(status -> operations.recategorize(userId, transactionId, categoryId, jarId)));
    }

    // -------------------------------------------------------------------- jars

    public JarView createJar(UserId userId, String name, BigDecimal targetPct) {
        return retry.run(() -> tx.execute(status -> operations.createJar(userId, name, targetPct)));
    }

    public JarView updateJar(UserId userId, UUID jarId, String name, BigDecimal targetPct) {
        return retry.run(() -> tx.execute(status -> operations.updateJar(userId, jarId, name, targetPct)));
    }

    public void archiveJar(UserId userId, UUID jarId) {
        retry.run(() -> tx.execute(status -> {
            operations.archiveJar(userId, jarId);
            return null;
        }));
    }
}
