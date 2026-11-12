package io.github.thphuc06.jars.budgeting.internal.service;

import io.github.thphuc06.jars.budgeting.internal.domain.AllocationPlanner;
import io.github.thphuc06.jars.budgeting.internal.domain.AllocationPlanner.JarProfile;
import io.github.thphuc06.jars.budgeting.internal.domain.AllocationPlanner.Plan;
import io.github.thphuc06.jars.budgeting.internal.domain.AllocationPlanner.Share;
import io.github.thphuc06.jars.budgeting.internal.domain.BudgetProblems;
import io.github.thphuc06.jars.budgeting.internal.persistence.JarPeriodStore;
import io.github.thphuc06.jars.budgeting.internal.persistence.JarPeriodStore.Spending;
import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suggests how to divide an income over the user's active jars: the planned percentages, nudged by what each jar
 * really spent in the last months (see {@link AllocationPlanner} for the rules). Read only; applying a split is
 * {@link AllocationService}.
 */
@Service
public class AllocationSuggestionService {

    /** How many complete months before the current one are looked at. */
    static final int HISTORY_MONTHS = AllocationPlanner.FULL_HISTORY_MONTHS;

    /** One jar of a suggestion, with its name and code so a screen needs no second call. */
    public record Line(UUID jarId, String code, String name, BigDecimal targetPct, BigDecimal sharePct, Money amount) {}

    public record Suggestion(Money amount, List<Line> lines, int monthsOfHistory, BigDecimal historyWeight) {}

    private final LedgerApi ledger;
    private final JarPeriodStore periods;
    private final Clock clock;

    AllocationSuggestionService(LedgerApi ledger, JarPeriodStore periods, Clock clock) {
        this.ledger = ledger;
        this.periods = periods;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Suggestion suggest(UserId userId, Money amount) {
        if (!amount.isPositive()) {
            throw new DomainException(BudgetProblems.INVALID_ALLOCATION, "Số tiền cần phân bổ phải lớn hơn 0.");
        }
        List<JarSummary> jars = ledger.jars(userId);
        if (jars.isEmpty()) {
            throw new DomainException(BudgetProblems.INVALID_ALLOCATION, "Bạn chưa có hũ nào để phân bổ.");
        }

        YearMonth current = Months.current(clock);
        List<Spending> history = periods.spendingBetween(
                userId, Months.startOf(current.minusMonths(HISTORY_MONTHS)), Months.startOf(current));
        Set<LocalDate> monthsWithSpending = new HashSet<>();
        Map<UUID, BigDecimal> totalSpent = new HashMap<>();
        for (Spending s : history) {
            monthsWithSpending.add(s.month());
            totalSpent.merge(s.jarId(), s.spent(), BigDecimal::add);
        }
        int months = monthsWithSpending.size();

        List<JarProfile> profiles = jars.stream()
                .map(j -> new JarProfile(
                        j.id(),
                        j.targetPct(),
                        months == 0
                                ? BigDecimal.ZERO
                                : totalSpent
                                        .getOrDefault(j.id(), BigDecimal.ZERO)
                                        .divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_EVEN)))
                .toList();
        Plan plan = AllocationPlanner.plan(amount, profiles, months);

        Map<UUID, JarSummary> byId = new HashMap<>();
        jars.forEach(j -> byId.put(j.id(), j));
        List<Line> lines = plan.shares().stream()
                .map((Share s) -> {
                    JarSummary jar = byId.get(s.jarId());
                    return new Line(jar.id(), jar.code(), jar.name(), jar.targetPct(), s.sharePct(), s.amount());
                })
                .toList();
        return new Suggestion(amount, lines, plan.monthsOfHistory(), plan.historyWeight());
    }
}
