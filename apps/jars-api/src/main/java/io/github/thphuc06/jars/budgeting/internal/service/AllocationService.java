package io.github.thphuc06.jars.budgeting.internal.service;

import io.github.thphuc06.jars.budgeting.internal.domain.BudgetProblems;
import io.github.thphuc06.jars.budgeting.internal.domain.DecisionStatus;
import io.github.thphuc06.jars.budgeting.internal.persistence.AllocationDecisionStore;
import io.github.thphuc06.jars.budgeting.internal.persistence.AllocationDecisionStore.Decision;
import io.github.thphuc06.jars.budgeting.internal.persistence.JarPeriodStore;
import io.github.thphuc06.jars.budgeting.internal.service.AllocationSuggestionService.Suggestion;
import io.github.thphuc06.jars.ledger.AllocationReceipt;
import io.github.thphuc06.jars.ledger.IncomeAllocation;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService.Result;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.ProblemTypes;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies the user's decision about a suggested split (flow: suggestion, then {@code POST /budget/allocations}).
 *
 * <p>Applying is two steps that each converge on a retry with the same Idempotency-Key, so a crash between them is
 * repaired by the client simply sending the request again:
 *
 * <ol>
 *   <li>The money moves through {@link LedgerApi#allocateIncome}, which is idempotent per key (its own transaction,
 *       retried on concurrent writes). A repeat gets the stored receipt instead of posting again.
 *   <li>The decision is recorded in one budgeting transaction: insert the {@code allocation_decisions} row (unique per
 *       {@code (user, key)}) and, only if this call inserted it, add the amounts to {@code jar_periods.planned_amount}. A
 *       repeat finds the row, adds nothing, and answers with the original decision.
 * </ol>
 *
 * <p>The two steps cannot share one transaction: the ledger retries optimistic-lock conflicts by running its own
 * transaction again, which only works when no transaction is open around it.
 */
@Service
public class AllocationService {

    /**
     * What the user asked for. {@code skip} declines the suggestion: nothing is posted and only the decision is
     * recorded; {@code splits} must then be empty.
     */
    public record ApplyCommand(
            Money amount,
            List<IncomeAllocation.Split> splits,
            UUID categoryId,
            String counterparty,
            String note,
            Instant occurredAt,
            boolean skip) {}

    /** What one jar received. */
    public record AppliedSplit(UUID jarId, Money amount, Money balance) {}

    /**
     * The outcome of a decision. {@code transactionId} and {@code splits} are empty for a skipped suggestion.
     *
     * @param month the budget month ({@code YYYY-MM}) the planned amounts were added to
     */
    public record AllocationResult(
            UUID decisionId,
            String decision,
            UUID transactionId,
            Money amount,
            String month,
            List<AppliedSplit> splits) {}

    /** {@code replayed} is true when this exact request was already decided before. */
    public record Applied(AllocationResult body, boolean replayed) {}

    /** A jar and an amount, as stored in {@code allocation_decisions.suggested/applied}. */
    record JarAmount(UUID jarId, Money amount) {}

    private final LedgerApi ledger;
    private final AllocationSuggestionService suggestions;
    private final AllocationDecisionStore decisions;
    private final JarPeriodStore periods;
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;

    AllocationService(
            LedgerApi ledger,
            AllocationSuggestionService suggestions,
            AllocationDecisionStore decisions,
            JarPeriodStore periods,
            IdempotencyService idempotency,
            TransactionTemplate tx,
            JsonMapper json,
            Clock clock) {
        this.ledger = ledger;
        this.suggestions = suggestions;
        this.decisions = decisions;
        this.periods = periods;
        this.idempotency = idempotency;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param requestHash identifies "the same request" for the key (see {@link IdempotencyService#hash})
     * @throws DomainException 404 for a jar that is not the caller's, 422 for an amount that is not positive, no
     *     splits (unless skipping), a repeated jar, splits that do not add up, or a key reused for another request
     */
    public Applied apply(UserId userId, String idempotencyKey, String requestHash, ApplyCommand cmd) {
        if (!cmd.amount().isPositive()) {
            throw invalid("Số tiền cần phân bổ phải lớn hơn 0.");
        }
        if (cmd.skip()) {
            if (cmd.splits() != null && !cmd.splits().isEmpty()) {
                throw invalid("Bỏ qua gợi ý thì không kèm phần chia cho các hũ.");
            }
            return skip(userId, idempotencyKey, requestHash, cmd);
        }
        if (cmd.splits() == null || cmd.splits().isEmpty()) {
            throw invalid("Cần chọn ít nhất một hũ để phân bổ.");
        }

        IncomeAllocation allocation = new IncomeAllocation(
                cmd.amount(), cmd.splits(), cmd.categoryId(), cmd.counterparty(), cmd.note(), cmd.occurredAt());
        Result<AllocationReceipt> receipt = ledger.allocateIncome(userId, idempotencyKey, requestHash, allocation);
        return record(userId, idempotencyKey, requestHash, cmd, receipt.body());
    }

    // ------------------------------------------------------------ the decision

    private Applied record(UserId userId, String key, String requestHash, ApplyCommand cmd, AllocationReceipt receipt) {
        Suggestion suggestion = suggestions.suggest(userId, cmd.amount());
        DecisionStatus status = matches(suggestion, cmd) ? DecisionStatus.ACCEPTED : DecisionStatus.EDITED;
        YearMonth month = Months.of(receipt.occurredAt());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String suggested = jarAmounts(suggestion.lines().stream().map(l -> new JarAmount(l.jarId(), l.amount())));
        String applied = jarAmounts(receipt.credits().stream().map(c -> new JarAmount(c.jarId(), c.amount())));

        return tx.execute(txStatus -> {
            UUID decisionId = Uuids.v7();
            boolean inserted = decisions.insertIfAbsent(
                    decisionId,
                    userId,
                    key,
                    requestHash,
                    status,
                    receipt.amount().amount(),
                    Months.startOf(month),
                    receipt.transactionId(),
                    suggested,
                    applied,
                    now);
            DecisionStatus finalStatus = status;
            if (inserted) {
                // the credits come sorted by jar id, so concurrent allocations lock the period rows in the same order
                receipt.credits()
                        .forEach(c -> periods.addPlanned(
                                userId,
                                c.jarId(),
                                Months.startOf(month),
                                c.amount().amount(),
                                now));
            } else {
                Decision existing = decisions
                        .find(userId, key)
                        .orElseThrow(() -> new IllegalStateException("Decision vanished for key " + key));
                if (!existing.requestHash().equals(requestHash)) {
                    throw new DomainException(
                            ProblemTypes.IDEMPOTENCY_KEY_REUSED,
                            "Idempotency-Key này đã được dùng cho một request khác.");
                }
                decisionId = existing.id();
                finalStatus = existing.status();
            }
            return new Applied(
                    new AllocationResult(
                            decisionId,
                            finalStatus.name(),
                            receipt.transactionId(),
                            receipt.amount(),
                            month.toString(),
                            receipt.credits().stream()
                                    .map(c -> new AppliedSplit(c.jarId(), c.amount(), c.balance()))
                                    .toList()),
                    !inserted);
        });
    }

    private Applied skip(UserId userId, String key, String requestHash, ApplyCommand cmd) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        YearMonth month = Months.of(cmd.occurredAt() == null ? now : cmd.occurredAt());
        Result<AllocationResult> result = tx.execute(txStatus ->
                idempotency.execute(userId, key, requestHash, AllocationResult.class, 201, () -> {
                    UUID decisionId = Uuids.v7();
                    Suggestion suggestion = suggestions.suggest(userId, cmd.amount());
                    decisions.insertIfAbsent(
                            decisionId,
                            userId,
                            key,
                            requestHash,
                            DecisionStatus.SKIPPED,
                            cmd.amount().amount(),
                            Months.startOf(month),
                            null,
                            jarAmounts(suggestion.lines().stream().map(l -> new JarAmount(l.jarId(), l.amount()))),
                            null,
                            now);
                    return new AllocationResult(
                            decisionId, DecisionStatus.SKIPPED.name(), null, cmd.amount(), month.toString(), List.of());
                }));
        return new Applied(result.body(), result.replayed());
    }

    // ------------------------------------------------------------ comparing with the suggestion

    /**
     * True when the requested split equals what the suggestion proposes for the same amount. A jar left out of the
     * request counts as 0, so leaving out a jar the suggestion gave nothing is not an edit.
     */
    private static boolean matches(Suggestion suggestion, ApplyCommand cmd) {
        Map<UUID, BigDecimal> proposed = new HashMap<>();
        suggestion.lines().forEach(l -> proposed.put(l.jarId(), l.amount().amount()));
        Map<UUID, BigDecimal> requested = new HashMap<>();
        cmd.splits().forEach(s -> requested.put(s.jarId(), s.amount().amount()));
        Set<UUID> jars = new HashSet<>(proposed.keySet());
        jars.addAll(requested.keySet());
        return jars.stream()
                .allMatch(id -> proposed.getOrDefault(id, BigDecimal.ZERO)
                                .compareTo(requested.getOrDefault(id, BigDecimal.ZERO))
                        == 0);
    }

    private String jarAmounts(Stream<JarAmount> amounts) {
        return json.writeValueAsString(amounts.toList());
    }

    private static DomainException invalid(String detail) {
        return new DomainException(BudgetProblems.INVALID_ALLOCATION, detail);
    }
}
