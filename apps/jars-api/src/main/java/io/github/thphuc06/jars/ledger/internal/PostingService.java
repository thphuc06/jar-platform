package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.ledger.TransactionRecategorized;
import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.shared.BusinessRuleViolationException;
import io.github.thphuc06.jars.shared.NotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Write side of the ledger: records INCOME, EXPENSE and JAR_TRANSFER (business-rules BR-2, flow F2).
 *
 * <p>Concurrency: each jar row carries a {@code version}. Two requests that read the same jar and both change it
 * cannot both win: the second {@code UPDATE ... WHERE version = ?} matches no row and Hibernate throws an optimistic
 * locking failure (no lost update). That attempt is rolled back and retried in a <b>new</b> transaction, which reads
 * the fresh balance and checks the rules again; so a retried expense may now fail with insufficient balance, which is
 * correct. The nesting is explicit on purpose: retry → transaction → work.
 */
@Service
public class PostingService {

    private static final Logger log = LoggerFactory.getLogger(PostingService.class);

    /** 1 attempt + 3 retries. Conflicts are rare (one user rarely writes from two places at once). */
    static final int MAX_RETRIES = 3;

    /** Tolerated clock difference between the client and the server for {@code occurredAt}. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final JarRepository jars;
    private final JarEntryRepository entries;
    private final LedgerTransactionRepository transactions;
    private final CategoryRepository categories;
    private final DefaultJarsProvisioner defaultJars;
    private final IdempotencyStore idempotency;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final RetryTemplate retry;

    PostingService(
            JarRepository jars,
            JarEntryRepository entries,
            LedgerTransactionRepository transactions,
            CategoryRepository categories,
            DefaultJarsProvisioner defaultJars,
            IdempotencyStore idempotency,
            ApplicationEventPublisher events,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jars = jars;
        this.entries = entries;
        this.transactions = transactions;
        this.categories = categories;
        this.defaultJars = defaultJars;
        this.idempotency = idempotency;
        this.events = events;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        // ConcurrencyFailureException covers optimistic locking failures and deadlocks; business errors are NOT
        // retried. Jitter spreads the retries of competing requests so they do not collide again.
        this.retry = new RetryTemplate(RetryPolicy.builder()
                .includes(ConcurrencyFailureException.class)
                .maxRetries(MAX_RETRIES)
                .delay(Duration.ofMillis(20))
                .jitter(Duration.ofMillis(10))
                .multiplier(2)
                .build());
        // visible in the logs (with the request's traceId): how often requests collide, and when they give up
        this.retry.setRetryListener(new RetryListener() {
            @Override
            public void beforeRetry(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
                log.info(
                        "posting conflict, retry {} of {}: {}",
                        state.getRetryCount(), // already counts the retry about to start
                        MAX_RETRIES,
                        state.getLastException().getClass().getSimpleName());
            }

            @Override
            public void onRetryPolicyExhaustion(RetryPolicy policy, Retryable<?> retryable, RetryException e) {
                log.warn("posting still conflicting after {} retries, giving up", MAX_RETRIES);
            }
        });
    }

    public record PostTransaction(
            TransactionType type,
            BigDecimal amount,
            @Nullable UUID jarId,
            @Nullable UUID categoryId,
            @Nullable String counterparty,
            @Nullable String note,
            @Nullable Instant occurredAt) {

        /** "85000" and "85000.00" are the same request: normalize before hashing for the Idempotency-Key. */
        PostTransaction normalized() {
            return new PostTransaction(
                    type, PostingService.normalized(amount), jarId, categoryId, counterparty, note, occurredAt);
        }
    }

    public record Transfer(
            UUID fromJarId,
            UUID toJarId,
            BigDecimal amount,
            @Nullable String note,
            @Nullable Instant occurredAt) {

        Transfer normalized() {
            return new Transfer(fromJarId, toJarId, PostingService.normalized(amount), note, occurredAt);
        }
    }

    /**
     * PATCH semantics per field: {@code null} = not sent, unchanged; {@code Optional.empty()} = sent as JSON null,
     * cleared; a value = new value. {@code jarId} cannot be cleared, so it is a plain nullable id.
     */
    public record Amendment(
            @Nullable Optional<UUID> categoryId,
            @Nullable Optional<String> note,
            @Nullable UUID jarId) {}

    /** The recorded transaction and the jars it changed, with their new balances. */
    public record Posting(TransactionView transaction, List<JarView> jars) {}

    /** POST /transactions: INCOME or EXPENSE. A transfer has its own endpoint and command. */
    public Idempotent<Posting> post(UUID userId, UUID idempotencyKey, PostTransaction command) {
        PostTransaction normalized = command.normalized();
        Supplier<Posting> work = switch (normalized.type()) {
            case INCOME -> () -> income(userId, normalized);
            case EXPENSE -> () -> expense(userId, normalized);
            case JAR_TRANSFER ->
                throw new BusinessRuleViolationException(
                        "use-transfer-endpoint", "Move money between jars with POST /api/v1/jars/transfers");
        };
        return inRetriedTransaction(userId, idempotencyKey, "POST /api/v1/transactions", normalized, work);
    }

    /** POST /jars/transfers, including allocating income out of POOL. */
    public Idempotent<Posting> transfer(UUID userId, UUID idempotencyKey, Transfer command) {
        if (command.fromJarId().equals(command.toJarId())) {
            throw new BusinessRuleViolationException("same-jar-transfer", "Source and destination jar are the same");
        }
        Transfer normalized = command.normalized();
        return inRetriedTransaction(
                userId,
                idempotencyKey,
                "POST /api/v1/jars/transfers",
                normalized,
                () -> jarTransfer(userId, normalized));
    }

    /**
     * PATCH /transactions/{id} (BR-2): category, note and jar may change; amount and type never do. Moving money to
     * another jar writes a reversing pair of ADJUST entries, so the ledger keeps the full history. Naturally
     * idempotent (applying the same change twice changes nothing the second time), so no Idempotency-Key is needed.
     */
    public Posting amend(UUID userId, UUID transactionId, Amendment change) {
        return retry.invoke(() -> transaction.execute(status -> applyAmendment(userId, transactionId, change)));
    }

    /**
     * Every attempt is a NEW database transaction, so it re-reads balances and versions and re-checks the rules. The
     * Idempotency-Key is reserved and completed inside that same transaction: a rolled-back attempt releases it.
     */
    private Idempotent<Posting> inRetriedTransaction(
            UUID userId, UUID idempotencyKey, String operation, Object request, Supplier<Posting> work) {
        return retry.invoke(() -> transaction.execute(
                status -> idempotency.run(userId, idempotencyKey, operation, request, Posting.class, work)));
    }

    private Posting income(UUID userId, PostTransaction command) {
        Jar jar = command.jarId() == null ? pool(userId) : ownJar(userId, command.jarId());
        UUID categoryId = checkedCategory(userId, command.categoryId(), CategoryKind.INCOME);
        BigDecimal amount = normalized(command.amount());

        LedgerTransaction income = LedgerTransaction.income(
                userId,
                amount,
                jar,
                categoryId,
                command.counterparty(),
                command.note(),
                occurredAt(command.occurredAt()));
        JarEntry entry = jar.deposit(amount, EntryType.INCOME, income.id());
        return record(income, List.of(entry), List.of(jar));
    }

    private Posting expense(UUID userId, PostTransaction command) {
        if (command.jarId() == null) {
            throw new BusinessRuleViolationException(
                    "expense-requires-jar", "An EXPENSE needs the jarId it is paid from");
        }
        Jar jar = ownJar(userId, command.jarId());
        if (jar.isPool()) {
            throw new BusinessRuleViolationException(
                    "expense-from-pool", "Unallocated money (POOL) must be moved to a jar before it is spent");
        }
        UUID categoryId = checkedCategory(userId, command.categoryId(), CategoryKind.EXPENSE);
        BigDecimal amount = normalized(command.amount());

        LedgerTransaction expense = LedgerTransaction.expense(
                userId,
                amount,
                jar,
                categoryId,
                command.counterparty(),
                command.note(),
                occurredAt(command.occurredAt()));
        JarEntry entry = jar.withdraw(amount, EntryType.SPEND, expense.id());
        return record(expense, List.of(entry), List.of(jar));
    }

    private Posting jarTransfer(UUID userId, Transfer command) {
        Jar from = ownJar(userId, command.fromJarId());
        Jar to = ownJar(userId, command.toJarId());
        BigDecimal amount = normalized(command.amount());

        LedgerTransaction transfer =
                LedgerTransaction.transfer(userId, amount, from, to, command.note(), occurredAt(command.occurredAt()));
        JarEntry out = from.withdraw(amount, EntryType.TRANSFER_OUT, transfer.id());
        JarEntry in = to.deposit(amount, EntryType.TRANSFER_IN, transfer.id());
        return record(transfer, List.of(out, in), List.of(from, to));
    }

    private Posting applyAmendment(UUID userId, UUID transactionId, Amendment change) {
        LedgerTransaction txn = transactions
                .findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new NotFoundException("transaction", transactionId));
        boolean isTransfer = txn.type() == TransactionType.JAR_TRANSFER;
        UUID oldCategoryId = txn.categoryId();
        UUID oldJarId = txn.jarId();

        if (change.categoryId() != null) {
            if (isTransfer) {
                throw new BusinessRuleViolationException(
                        "transfer-has-no-category", "A transfer between jars has no category");
            }
            txn.changeCategory(checkedCategory(userId, change.categoryId().orElse(null), categoryKindOf(txn)));
        }
        if (change.note() != null) {
            txn.changeNote(change.note().filter(note -> !note.isBlank()).orElse(null));
        }

        List<Jar> changedJars = new ArrayList<>();
        if (change.jarId() != null && !change.jarId().equals(oldJarId)) {
            if (isTransfer) {
                throw new BusinessRuleViolationException(
                        "transfer-jars-immutable", "Record a new transfer instead of moving an existing one");
            }
            Jar from = ownJar(userId, oldJarId);
            Jar to = ownJar(userId, change.jarId());
            List<JarEntry> reversal;
            if (txn.type() == TransactionType.EXPENSE) {
                if (to.isPool()) {
                    throw new BusinessRuleViolationException(
                            "expense-from-pool", "Unallocated money (POOL) must be moved to a jar before it is spent");
                }
                // the old jar gets the money back, the new jar pays (and must hold enough: BR-2)
                JarEntry refund = from.deposit(txn.amount(), EntryType.ADJUST, txn.id());
                reversal = List.of(refund, to.withdraw(txn.amount(), EntryType.ADJUST, txn.id()));
            } else {
                // income already allocated or spent from the old jar cannot be taken back from it
                JarEntry takeBack = from.withdraw(txn.amount(), EntryType.ADJUST, txn.id());
                reversal = List.of(takeBack, to.deposit(txn.amount(), EntryType.ADJUST, txn.id()));
            }
            txn.moveTo(to);
            entries.saveAll(reversal);
            changedJars.add(from);
            changedJars.add(to);
        }

        boolean bucketChanged = !Objects.equals(oldCategoryId, txn.categoryId()) || !oldJarId.equals(txn.jarId());
        if (bucketChanged) {
            events.publishEvent(new TransactionRecategorized(
                    txn.id(),
                    userId,
                    txn.type(),
                    txn.amount(),
                    oldCategoryId,
                    txn.categoryId(),
                    oldJarId,
                    txn.jarId(),
                    txn.occurredAt()));
        }
        transactions.flush(); // version-checked UPDATEs of the transaction and the jars, inside this attempt
        return new Posting(
                TransactionView.of(txn), changedJars.stream().map(JarView::of).toList());
    }

    private static CategoryKind categoryKindOf(LedgerTransaction txn) {
        return txn.type() == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
    }

    /**
     * Saves in foreign-key order (transaction before its entries) and publishes the event into the outbox, all in the
     * current transaction. {@code flush()} sends the version-checked UPDATEs of the jars NOW, so a conflict surfaces
     * inside the attempt instead of at commit.
     */
    private Posting record(LedgerTransaction txn, List<JarEntry> newEntries, List<Jar> changedJars) {
        transactions.save(txn);
        entries.saveAll(newEntries);
        events.publishEvent(txn.toEvent());
        jars.flush();
        return new Posting(
                TransactionView.of(txn), changedJars.stream().map(JarView::of).toList());
    }

    private Jar ownJar(UUID userId, UUID jarId) {
        return jars.findByIdAndUserId(jarId, userId).orElseThrow(() -> new NotFoundException("jar", jarId));
    }

    /** POOL exists once the default jars do; they are created on demand if the async listener has not run yet. */
    private Jar pool(UUID userId) {
        return jars.findByUserIdAndCode(userId, JarCode.POOL).orElseGet(() -> {
            defaultJars.ensureFor(userId);
            return jars.findByUserIdAndCode(userId, JarCode.POOL).orElseThrow();
        });
    }

    private @Nullable UUID checkedCategory(UUID userId, @Nullable UUID categoryId, CategoryKind expected) {
        if (categoryId == null) {
            return null;
        }
        Category category = categories
                .findVisible(categoryId, userId)
                .orElseThrow(() -> new NotFoundException("category", categoryId));
        if (category.kind() != expected) {
            throw new BusinessRuleViolationException(
                    "category-kind-mismatch",
                    "Category " + categoryId + " is " + category.kind() + ", not " + expected);
        }
        return categoryId;
    }

    /** BR-5: the web layer already rejected decimals ({@code @VndAmount}); this fails loudly if that ever changes. */
    static BigDecimal normalized(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private Instant occurredAt(@Nullable Instant requested) {
        // PostgreSQL keeps microseconds: truncate now, so the value returned by POST equals the one read back later
        // (and a keyset cursor built from it matches the stored row exactly)
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (requested == null) {
            return now;
        }
        if (requested.isAfter(now.plus(CLOCK_SKEW))) {
            throw new BusinessRuleViolationException("occurred-at-in-future", "occurredAt is in the future");
        }
        return requested.truncatedTo(ChronoUnit.MICROS);
    }
}
