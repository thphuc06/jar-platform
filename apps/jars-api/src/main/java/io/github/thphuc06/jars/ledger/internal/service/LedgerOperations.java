package io.github.thphuc06.jars.ledger.internal.service;

import io.github.thphuc06.jars.ledger.AllocationReceipt;
import io.github.thphuc06.jars.ledger.IncomeAllocation;
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
import io.github.thphuc06.jars.ledger.internal.domain.TransactionSource;
import io.github.thphuc06.jars.ledger.internal.domain.TransactionType;
import io.github.thphuc06.jars.ledger.internal.persistence.CategoryRepository;
import io.github.thphuc06.jars.ledger.internal.persistence.JarEntryRepository;
import io.github.thphuc06.jars.ledger.internal.persistence.JarRepository;
import io.github.thphuc06.jars.ledger.internal.persistence.TransactionRepository;
import io.github.thphuc06.jars.ledger.internal.service.LedgerCommandService.PostTransactionCommand;
import io.github.thphuc06.jars.ledger.internal.service.LedgerCommandService.TransferCommand;
import io.github.thphuc06.jars.ledger.internal.service.Views.JarBalance;
import io.github.thphuc06.jars.ledger.internal.service.Views.JarView;
import io.github.thphuc06.jars.ledger.internal.service.Views.TransactionReceipt;
import io.github.thphuc06.jars.ledger.internal.service.Views.TransactionView;
import io.github.thphuc06.jars.platform.audit.Audit;
import io.github.thphuc06.jars.platform.audit.AuditAction;
import io.github.thphuc06.jars.platform.audit.AuditEntry;
import io.github.thphuc06.jars.platform.audit.Audited;
import io.github.thphuc06.jars.platform.observability.AfterCommit;
import io.github.thphuc06.jars.platform.observability.BusinessMetrics;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The business step of every ledger command: validate, write entries and balances, publish the event, and describe
 * what happened for the audit log. It always runs inside a transaction that {@link LedgerCommandService} has opened,
 * after the idempotency key was taken, inside the retry loop:
 *
 * <pre>
 * LedgerCommandService:  retry  →  transaction  →  idempotency key  →  (this bean, @Audited)  →  rules, entries, event
 * </pre>
 *
 * <p>That is why this is a bean of its own. {@code @Audited} works through a Spring proxy, and a method that calls
 * another method of its own class bypasses the proxy; putting the annotation on {@code LedgerCommandService.post}
 * would also sit outside the retry loop and the transaction. Here the audit row is written in the attempt's own
 * transaction: an attempt that loses the optimistic-lock race (the conflict surfaces at the commit, after the row was
 * inserted) rolls the row back with the rest, and a replayed idempotent request never reaches this bean at all.
 */
@Service
class LedgerOperations {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Duration MAX_FUTURE = Duration.ofDays(1);

    private final JarRepository jars;
    private final JarEntryRepository entries;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final LedgerProperties properties;
    private final MeterRegistry meters;

    LedgerOperations(
            JarRepository jars,
            JarEntryRepository entries,
            TransactionRepository transactions,
            CategoryRepository categories,
            ApplicationEventPublisher events,
            Clock clock,
            LedgerProperties properties,
            MeterRegistry meters) {
        this.jars = jars;
        this.entries = entries;
        this.transactions = transactions;
        this.categories = categories;
        this.events = events;
        this.clock = clock;
        this.properties = properties;
        this.meters = meters;
    }

    // ---------------------------------------------------------------- postings

    /** A posting made by the user: audited. */
    @Audited(AuditAction.TRANSACTION_POSTED)
    public TransactionReceipt post(UserId userId, PostTransactionCommand cmd) {
        TransactionReceipt receipt = doPost(userId, cmd, TransactionSource.MANUAL);
        TransactionView t = receipt.transaction();
        AuditEntry entry = Audit.entity(t.id()).with("type", cmd.type()).with("amount", t.amount());
        if (t.jarId() != null) {
            entry.with("jarId", t.jarId());
        }
        if (t.categoryId() != null) {
            entry.with("categoryId", t.categoryId());
        }
        return receipt;
    }

    /**
     * One row of an imported statement. Not audited here: an import is audited once, when it is created, not once per
     * line (a statement can have thousands).
     */
    public TransactionReceipt postImported(UserId userId, PostTransactionCommand cmd) {
        return doPost(userId, cmd, TransactionSource.IMPORT);
    }

    @Audited(AuditAction.JAR_TRANSFERRED)
    public TransactionReceipt transfer(UserId userId, TransferCommand cmd) {
        TransactionReceipt receipt = doTransfer(userId, cmd);
        Audit.entity(receipt.transaction().id())
                .with("amount", cmd.amount())
                .with("fromJarId", cmd.fromJarId())
                .with("toJarId", cmd.toJarId());
        return receipt;
    }

    @Audited(AuditAction.INCOME_ALLOCATED)
    public AllocationReceipt allocateIncome(UserId userId, IncomeAllocation cmd) {
        AllocationReceipt receipt = doAllocateIncome(userId, cmd);
        Audit.entity(receipt.transactionId())
                .with("amount", receipt.amount())
                .withIds(
                        "jarIds",
                        receipt.credits().stream()
                                .map(AllocationReceipt.Credit::jarId)
                                .toList());
        return receipt;
    }

    /** Changes the category and/or jar of an existing transaction; a null argument keeps the current value. */
    @Audited(AuditAction.TRANSACTION_RECATEGORIZED)
    public TransactionView recategorize(UserId userId, UUID transactionId, UUID categoryId, UUID jarId) {
        return doRecategorize(userId, transactionId, categoryId, jarId);
    }

    private TransactionReceipt doPost(UserId userId, PostTransactionCommand cmd, TransactionSource source) {
        Instant now = now();
        Instant occurredAt = cmd.occurredAt() == null ? now : cmd.occurredAt().truncatedTo(ChronoUnit.MICROS);
        if (occurredAt.isAfter(now.plus(MAX_FUTURE))) {
            throw invalid("Ngày giao dịch không được ở tương lai quá 1 ngày.");
        }
        requirePositive(cmd.amount());

        return switch (cmd.type()) {
            case EXPENSE -> postExpense(userId, cmd, occurredAt, now, source);
            case INCOME -> postIncome(userId, cmd, occurredAt, now, source);
            case JAR_TRANSFER -> throw invalid("Chuyển tiền giữa các hũ dùng POST /api/v1/jars/transfers.");
        };
    }

    private TransactionReceipt postExpense(
            UserId userId, PostTransactionCommand cmd, Instant occurredAt, Instant now, TransactionSource source) {
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
                source,
                now));
        entries.save(jar.post(EntryType.SPEND, cmd.amount().negate(), t.getId(), occurredAt, now));
        publishPosted(userId, t);
        return new TransactionReceipt(TransactionView.of(t), List.of(new JarBalance(jar.getId(), jar.balance())));
    }

    private TransactionReceipt postIncome(
            UserId userId, PostTransactionCommand cmd, Instant occurredAt, Instant now, TransactionSource source) {
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
                source,
                now));
        List<JarBalance> balances = new ArrayList<>();
        if (jar != null) {
            entries.save(jar.post(EntryType.ALLOCATE, cmd.amount(), t.getId(), occurredAt, now));
            balances.add(new JarBalance(jar.getId(), jar.balance()));
        }
        publishPosted(userId, t);
        return new TransactionReceipt(TransactionView.of(t), balances);
    }

    private AllocationReceipt doAllocateIncome(UserId userId, IncomeAllocation cmd) {
        Instant now = now();
        Instant occurredAt = cmd.occurredAt() == null ? now : cmd.occurredAt().truncatedTo(ChronoUnit.MICROS);
        if (occurredAt.isAfter(now.plus(MAX_FUTURE))) {
            throw invalid("Ngày giao dịch không được ở tương lai quá 1 ngày.");
        }
        requirePositive(cmd.amount());
        List<IncomeAllocation.Split> splits = validSplits(cmd);
        requireCategory(userId, cmd.categoryId(), CategoryKind.INCOME);

        // lock order: every writer touches the jars in the same order, so two allocations cannot deadlock each other
        List<IncomeAllocation.Split> ordered = new ArrayList<>(splits);
        ordered.sort(Comparator.comparing(IncomeAllocation.Split::jarId));
        List<Jar> targets = new ArrayList<>();
        for (IncomeAllocation.Split split : ordered) {
            targets.add(requireJar(userId, split.jarId()));
        }

        LedgerTransaction t = transactions.save(LedgerTransaction.income(
                userId.value(), cmd.amount(), null, cmd.categoryId(), cmd.counterparty(), cmd.note(), occurredAt, now));
        List<AllocationReceipt.Credit> credits = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Jar jar = targets.get(i);
            Money share = ordered.get(i).amount();
            entries.save(jar.post(EntryType.ALLOCATE, share, t.getId(), occurredAt, now));
            credits.add(new AllocationReceipt.Credit(jar.getId(), share, jar.balance()));
        }
        publishPosted(userId, t);
        return new AllocationReceipt(t.getId(), t.money(), occurredAt, credits);
    }

    /** The splits of an allocation, after checking that each is positive, no jar repeats and they add up to the amount. */
    private static List<IncomeAllocation.Split> validSplits(IncomeAllocation cmd) {
        List<IncomeAllocation.Split> splits = cmd.splits();
        if (splits == null || splits.isEmpty()) {
            throw invalid("Cần chọn ít nhất một hũ để phân bổ.");
        }
        Set<UUID> seen = new HashSet<>();
        Money total = Money.zero(cmd.amount().currency());
        for (IncomeAllocation.Split split : splits) {
            if (split.jarId() == null) {
                throw invalid("Thiếu hũ nhận tiền.");
            }
            if (!split.amount().currency().equals(cmd.amount().currency())) {
                throw invalid("Mọi phần phân bổ phải cùng loại tiền với khoản thu.");
            }
            if (!split.amount().isPositive()) {
                throw invalid("Số tiền phân bổ cho mỗi hũ phải lớn hơn 0.");
            }
            if (!seen.add(split.jarId())) {
                throw invalid("Mỗi hũ chỉ xuất hiện một lần trong phân bổ.");
            }
            total = total.plus(split.amount());
        }
        if (total.compareTo(cmd.amount()) != 0) {
            throw new DomainException(
                    LedgerProblems.ALLOCATION_SUM_MISMATCH,
                    "Các phần phân bổ cộng lại " + total + ", khác số tiền " + cmd.amount() + ".");
        }
        return splits;
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
                    Uuids.v7(),
                    t.getId(),
                    userId,
                    t.getType().name(),
                    t.money(),
                    t.getOccurredAt(),
                    oldCategoryId,
                    t.getCategoryId(),
                    oldJarId,
                    t.getJarId()));
            Audit.entity(t.getId())
                    .with("amount", t.money())
                    .with("movedBetweenJars", newJar != null) // true: a reversing ADJUST entry and a new SPEND entry
                    .with("categoryChanged", !Objects.equals(oldCategoryId, t.getCategoryId()));
            if (newJar != null) {
                Audit.entity(t.getId()).with("fromJarId", oldJarId).with("toJarId", t.getJarId());
            }
        } else {
            Audit.unchanged(); // the same category and jar as before: nothing was written
        }
        return TransactionView.of(t);
    }

    // -------------------------------------------------------------------- jars

    @Audited(AuditAction.JAR_CREATED)
    public JarView createJar(UserId userId, String name, BigDecimal targetPct) {
        lockBudget(userId);
        BigDecimal pct = targetPct == null ? BigDecimal.ZERO : targetPct;
        requireBudgetRoom(userId, pct, BigDecimal.ZERO);
        Jar jar = jars.save(new Jar(userId.value(), JarCode.CUSTOM, name.trim(), pct, Money.VND, now()));
        events.publishEvent(
                new JarCreated(Uuids.v7(), jar.getId(), userId, jar.getCode().name()));
        Audit.entity(jar.getId()).with("targetPct", pct); // the name is free text the user chose: not recorded
        return JarView.of(jar);
    }

    @Audited(AuditAction.JAR_UPDATED)
    public JarView updateJar(UserId userId, UUID jarId, String name, BigDecimal targetPct) {
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
        Audit.entity(jar.getId()).with("renamed", name != null).with("targetPct", jar.getTargetPct());
        return JarView.of(jar);
    }

    @Audited(AuditAction.JAR_ARCHIVED)
    public void archiveJar(UserId userId, UUID jarId) {
        lockBudget(userId);
        requireJar(userId, jarId).archive(now());
        Audit.entity(jarId);
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
                Uuids.v7(),
                t.getId(),
                userId,
                t.getType().name(),
                t.money(),
                t.getJarId(),
                t.getCategoryId(),
                t.getOccurredAt(),
                t.getSource().name()));
        // counted after commit: an attempt that loses the optimistic-lock race is rolled back and retried
        String type = t.getType().name();
        String source = t.getSource().name();
        AfterCommit.run(() -> meters.counter(BusinessMetrics.TRANSACTIONS_POSTED, "type", type, "source", source)
                .increment());
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
