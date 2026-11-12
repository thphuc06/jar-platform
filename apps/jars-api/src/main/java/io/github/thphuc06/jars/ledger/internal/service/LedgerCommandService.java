package io.github.thphuc06.jars.ledger.internal.service;

import io.github.thphuc06.jars.ledger.JarCreated;
import io.github.thphuc06.jars.ledger.TransactionPosted;
import io.github.thphuc06.jars.ledger.TransactionRecategorized;
import io.github.thphuc06.jars.ledger.internal.domain.Category;
import io.github.thphuc06.jars.ledger.internal.domain.CategoryKind;
import io.github.thphuc06.jars.ledger.internal.domain.EntryType;
import io.github.thphuc06.jars.ledger.internal.domain.Jar;
import io.github.thphuc06.jars.ledger.internal.domain.JarCode;
import io.github.thphuc06.jars.ledger.internal.domain.LedgerProblems;
import io.github.thphuc06.jars.ledger.internal.domain.LedgerTransaction;
import io.github.thphuc06.jars.ledger.internal.domain.TransactionType;
import io.github.thphuc06.jars.ledger.internal.persistence.CategoryRepository;
import io.github.thphuc06.jars.ledger.internal.persistence.JarEntryRepository;
import io.github.thphuc06.jars.ledger.internal.persistence.JarRepository;
import io.github.thphuc06.jars.ledger.internal.persistence.TransactionRepository;
import io.github.thphuc06.jars.ledger.internal.service.Views.JarBalance;
import io.github.thphuc06.jars.ledger.internal.service.Views.JarView;
import io.github.thphuc06.jars.ledger.internal.service.Views.TransactionReceipt;
import io.github.thphuc06.jars.ledger.internal.service.Views.TransactionView;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService.Result;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
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
 */
@Service
public class LedgerCommandService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Duration MAX_FUTURE = Duration.ofDays(1);

    public record PostTransactionCommand(
            TransactionType type,
            Money amount,
            UUID jarId,
            UUID categoryId,
            String counterparty,
            String note,
            Instant occurredAt) {}

    public record TransferCommand(UUID fromJarId, UUID toJarId, Money amount, String note) {}

    private final JarRepository jars;
    private final JarEntryRepository entries;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final IdempotencyService idempotency;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final LedgerProperties properties;
    private final OptimisticRetry retry;

    LedgerCommandService(
            JarRepository jars,
            JarEntryRepository entries,
            TransactionRepository transactions,
            CategoryRepository categories,
            IdempotencyService idempotency,
            ApplicationEventPublisher events,
            TransactionTemplate tx,
            Clock clock,
            LedgerProperties properties) {
        this.jars = jars;
        this.entries = entries;
        this.transactions = transactions;
        this.categories = categories;
        this.idempotency = idempotency;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
        this.properties = properties;
        this.retry = OptimisticRetry.withJitter(properties.maxAttempts());
    }

    // ---------------------------------------------------------------- postings

    public Result<TransactionReceipt> post(
            UserId userId, String idempotencyKey, String requestHash, PostTransactionCommand cmd) {
        return retry.run(() -> tx.execute(status -> idempotency.execute(
                userId, idempotencyKey, requestHash, TransactionReceipt.class, 201, () -> doPost(userId, cmd))));
    }

    public Result<TransactionReceipt> transfer(
            UserId userId, String idempotencyKey, String requestHash, TransferCommand cmd) {
        return retry.run(() -> tx.execute(status -> idempotency.execute(
                userId, idempotencyKey, requestHash, TransactionReceipt.class, 201, () -> doTransfer(userId, cmd))));
    }

    /** Changes the category and/or jar of an existing transaction; a null argument keeps the current value. */
    public TransactionView recategorize(UserId userId, UUID transactionId, UUID categoryId, UUID jarId) {
        return retry.run(() -> tx.execute(status -> doRecategorize(userId, transactionId, categoryId, jarId)));
    }

    private TransactionReceipt doPost(UserId userId, PostTransactionCommand cmd) {
        Instant now = now();
        Instant occurredAt = cmd.occurredAt() == null ? now : cmd.occurredAt().truncatedTo(ChronoUnit.MICROS);
        if (occurredAt.isAfter(now.plus(MAX_FUTURE))) {
            throw invalid("Ngày giao dịch không được ở tương lai quá 1 ngày.");
        }
        requirePositive(cmd.amount());

        return switch (cmd.type()) {
            case EXPENSE -> postExpense(userId, cmd, occurredAt, now);
            case INCOME -> postIncome(userId, cmd, occurredAt, now);
            case JAR_TRANSFER -> throw invalid("Chuyển tiền giữa các hũ dùng POST /api/v1/jars/transfers.");
        };
    }

    private TransactionReceipt postExpense(UserId userId, PostTransactionCommand cmd, Instant occurredAt, Instant now) {
        if (cmd.jarId() == null) {
            throw invalid("Khoản chi cần chọn hũ để trừ tiền.");
        }
        Jar jar = requireJar(userId, cmd.jarId());
        requireCategory(userId, cmd.categoryId(), CategoryKind.EXPENSE);
        if (properties.blockOverspend()) {
            jar.requireAtLeast(cmd.amount());
        }
        LedgerTransaction t = transactions.save(LedgerTransaction.expense(
                userId.value(),
                cmd.amount(),
                jar.getId(),
                cmd.categoryId(),
                cmd.counterparty(),
                cmd.note(),
                occurredAt,
                now));
        entries.save(jar.post(EntryType.SPEND, cmd.amount().negate(), t.getId(), occurredAt, now));
        publishPosted(userId, t);
        return new TransactionReceipt(TransactionView.of(t), List.of(new JarBalance(jar.getId(), jar.balance())));
    }

    private TransactionReceipt postIncome(UserId userId, PostTransactionCommand cmd, Instant occurredAt, Instant now) {
        Jar jar = cmd.jarId() == null ? null : requireJar(userId, cmd.jarId());
        requireCategory(userId, cmd.categoryId(), CategoryKind.INCOME);
        LedgerTransaction t = transactions.save(LedgerTransaction.income(
                userId.value(),
                cmd.amount(),
                jar == null ? null : jar.getId(),
                cmd.categoryId(),
                cmd.counterparty(),
                cmd.note(),
                occurredAt,
                now));
        List<JarBalance> balances = new ArrayList<>();
        if (jar != null) {
            entries.save(jar.post(EntryType.ALLOCATE, cmd.amount(), t.getId(), occurredAt, now));
            balances.add(new JarBalance(jar.getId(), jar.balance()));
        }
        publishPosted(userId, t);
        return new TransactionReceipt(TransactionView.of(t), balances);
    }

    private TransactionReceipt doTransfer(UserId userId, TransferCommand cmd) {
        Instant now = now();
        requirePositive(cmd.amount());
        if (cmd.fromJarId().equals(cmd.toJarId())) {
            throw invalid("Hũ nguồn và hũ đích phải khác nhau.");
        }
        Jar from = requireJar(userId, cmd.fromJarId());
        Jar to = requireJar(userId, cmd.toJarId());
        from.requireAtLeast(cmd.amount());

        LedgerTransaction t = transactions.save(
                LedgerTransaction.transfer(userId.value(), cmd.amount(), from.getId(), to.getId(), cmd.note(), now));
        entries.save(from.post(EntryType.TRANSFER_OUT, cmd.amount().negate(), t.getId(), now, now));
        entries.save(to.post(EntryType.TRANSFER_IN, cmd.amount(), t.getId(), now, now));
        publishPosted(userId, t);
        return new TransactionReceipt(
                TransactionView.of(t),
                List.of(new JarBalance(from.getId(), from.balance()), new JarBalance(to.getId(), to.balance())));
    }

    private TransactionView doRecategorize(UserId userId, UUID transactionId, UUID categoryId, UUID jarId) {
        LedgerTransaction t = transactions
                .findByIdAndUserId(transactionId, userId.value())
                .orElseThrow(() -> DomainException.notFound("Giao dịch"));
        if (t.getType() == TransactionType.JAR_TRANSFER) {
            throw invalid("Không thể sửa giao dịch chuyển hũ.");
        }
        if (t.getType() == TransactionType.INCOME && jarId != null && !jarId.equals(t.getJarId())) {
            throw invalid("Thu nhập đã ghi vào hũ không thể chuyển sang hũ khác.");
        }
        if (categoryId != null) {
            requireCategory(
                    userId,
                    categoryId,
                    t.getType() == TransactionType.EXPENSE ? CategoryKind.EXPENSE : CategoryKind.INCOME);
        }

        UUID oldCategoryId = t.getCategoryId();
        UUID oldJarId = t.getJarId();
        UUID targetJarId = t.getType() == TransactionType.EXPENSE ? jarId : null;
        Jar newJar = targetJarId != null && !targetJarId.equals(oldJarId) ? requireJar(userId, targetJarId) : null;

        if (newJar != null) {
            // the expense moves from one jar to another: give the money back to the old jar and take it from the new
            // one
            Instant now = now();
            Jar oldJar = requireJar(userId, oldJarId);
            Money amount = t.money();
            entries.save(oldJar.post(EntryType.ADJUST, amount, t.getId(), now, now));
            entries.save(newJar.post(EntryType.SPEND, amount.negate(), t.getId(), now, now));
        }
        boolean changed = t.recategorize(categoryId, targetJarId);
        if (changed) {
            events.publishEvent(new TransactionRecategorized(
                    t.getId(), userId, oldCategoryId, t.getCategoryId(), oldJarId, t.getJarId()));
        }
        return TransactionView.of(t);
    }

    // -------------------------------------------------------------------- jars

    public JarView createJar(UserId userId, String name, BigDecimal targetPct) {
        return retry.run(() -> tx.execute(status -> {
            lockBudget(userId);
            BigDecimal pct = targetPct == null ? BigDecimal.ZERO : targetPct;
            requireBudgetRoom(userId, pct, BigDecimal.ZERO);
            Jar jar = jars.save(new Jar(userId.value(), JarCode.CUSTOM, name.trim(), pct, Money.VND, now()));
            events.publishEvent(
                    new JarCreated(jar.getId(), userId, jar.getCode().name()));
            return JarView.of(jar);
        }));
    }

    public JarView updateJar(UserId userId, UUID jarId, String name, BigDecimal targetPct) {
        return retry.run(() -> tx.execute(status -> {
            lockBudget(userId);
            Jar jar = requireJar(userId, jarId);
            Instant now = now();
            if (name != null) {
                jar.rename(name.trim(), now);
            }
            if (targetPct != null) {
                requireBudgetRoom(userId, targetPct, jar.getTargetPct());
                jar.changeTargetPct(targetPct, now);
            }
            return JarView.of(jar);
        }));
    }

    public void archiveJar(UserId userId, UUID jarId) {
        retry.run(() -> tx.execute(status -> {
            lockBudget(userId);
            requireJar(userId, jarId).archive(now());
            return null;
        }));
    }

    // ----------------------------------------------------------------- helpers

    /** The caller's active jar, or 404: a jar of someone else is indistinguishable from one that does not exist. */
    private Jar requireJar(UserId userId, UUID jarId) {
        Jar jar = jars.findByIdAndUserId(jarId, userId.value()).orElseThrow(() -> DomainException.notFound("Hũ"));
        jar.requireActive();
        return jar;
    }

    private Category requireCategory(UserId userId, UUID categoryId, CategoryKind expected) {
        if (categoryId == null) {
            return null;
        }
        Category category = categories
                .findVisibleById(categoryId, userId.value())
                .orElseThrow(() -> DomainException.notFound("Danh mục"));
        if (category.getKind() != expected) {
            throw invalid("Danh mục \"" + category.getName() + "\" không dùng cho loại giao dịch này.");
        }
        return category;
    }

    private void requireBudgetRoom(UserId userId, BigDecimal newPct, BigDecimal currentPct) {
        if (newPct.signum() < 0 || newPct.compareTo(HUNDRED) > 0) {
            throw invalid("Tỷ lệ hũ phải nằm trong khoảng 0 đến 100.");
        }
        BigDecimal total =
                jars.sumActiveTargetPct(userId.value()).subtract(currentPct).add(newPct);
        if (total.compareTo(HUNDRED) > 0) {
            throw new DomainException(
                    LedgerProblems.ALLOCATION_EXCEEDS_100,
                    "Tổng tỷ lệ các hũ sẽ là " + total.stripTrailingZeros().toPlainString()
                            + "%, vượt 100%. Hãy giảm tỷ lệ của hũ khác trước.");
        }
    }

    private void lockBudget(UserId userId) {
        jars.lockBudgetOf(
                userId.value().getMostSignificantBits() ^ userId.value().getLeastSignificantBits());
    }

    private void publishPosted(UserId userId, LedgerTransaction t) {
        events.publishEvent(new TransactionPosted(
                t.getId(),
                userId,
                t.getType().name(),
                t.money(),
                t.getJarId(),
                t.getCategoryId(),
                t.getOccurredAt(),
                t.getSource().name()));
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw invalid("Số tiền phải lớn hơn 0.");
        }
    }

    private static DomainException invalid(String detail) {
        return new DomainException(LedgerProblems.INVALID_TRANSACTION, detail);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
