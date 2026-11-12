package io.github.thphuc06.jars.goals.internal.service;

import io.github.thphuc06.jars.goals.internal.domain.CashflowStats;
import io.github.thphuc06.jars.goals.internal.domain.CashflowStats.MonthFlow;
import io.github.thphuc06.jars.goals.internal.domain.Goal;
import io.github.thphuc06.jars.insights.InsightsApi;
import io.github.thphuc06.jars.insights.MonthlyCashflow;
import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers what the feasibility, scenario and roadmap services all start from: the goal, how much its funding jar holds,
 * what all the jars hold together, the recent cashflow and the open anomalies. Money and history come from the public APIs
 * of the ledger and insights modules, never from their tables.
 */
@Service
public class GoalAnalysis {

    /** One more than {@link CashflowStats#MAX_MONTHS}: the running month is fetched too and dropped when others exist. */
    static final int HISTORY_MONTHS = CashflowStats.MAX_MONTHS + 1;

    /**
     * @param fundingJarActive false when the goal has no funding jar or its jar is archived: its current amount is 0
     * @param totalBalance what all the active jars hold together
     */
    public record Snapshot(
            Goal goal,
            LocalDate today,
            Money currentAmount,
            boolean fundingJarActive,
            Money totalBalance,
            CashflowStats cashflow,
            long openAnomalies) {

        public UUID goalId() {
            return goal.getId();
        }

        public Money target() {
            return goal.target();
        }
    }

    private final GoalService goals;
    private final LedgerApi ledger;
    private final InsightsApi insights;

    GoalAnalysis(GoalService goals, LedgerApi ledger, InsightsApi insights) {
        this.goals = goals;
        this.ledger = ledger;
        this.insights = insights;
    }

    /** @throws io.github.thphuc06.jars.shared.DomainException 404 when the goal is not the caller's or is archived */
    @Transactional(readOnly = true)
    public Snapshot snapshot(UserId userId, UUID goalId) {
        Goal goal = goals.require(userId, goalId);
        LocalDate today = goals.today();

        List<JarSummary> jars = ledger.jars(userId);
        Money total = jars.stream().map(JarSummary::balance).reduce(Money.zero(Money.VND), Money::plus);
        JarSummary funding = goal.getFundingJarId() == null
                ? null
                : jars.stream()
                        .filter(j -> j.id().equals(goal.getFundingJarId()))
                        .findFirst()
                        .orElse(null);
        Money current = funding == null ? Money.zero(Money.VND) : funding.balance();

        List<MonthFlow> flows = insights.monthlyCashflow(userId, HISTORY_MONTHS).stream()
                .map(GoalAnalysis::toFlow)
                .toList();
        CashflowStats stats = CashflowStats.of(flows, YearMonth.from(today));
        return new Snapshot(goal, today, current, funding != null, total, stats, insights.openAnomalyCount(userId));
    }

    private static MonthFlow toFlow(MonthlyCashflow m) {
        return new MonthFlow(m.month(), m.income().amount(), m.expense().amount());
    }

    /** The default seed of a goal: derived from its id so a goal always gives the same simulation unless asked otherwise. */
    public static long seedOf(UUID goalId) {
        return goalId.getMostSignificantBits() ^ Long.rotateLeft(goalId.getLeastSignificantBits(), 17);
    }
}
