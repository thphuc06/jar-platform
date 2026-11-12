package io.github.thphuc06.jars.goals.internal.service;

import io.github.thphuc06.jars.goals.internal.domain.CashflowStats;
import io.github.thphuc06.jars.goals.internal.domain.FeasibilityCalculator;
import io.github.thphuc06.jars.goals.internal.domain.GoalProblems;
import io.github.thphuc06.jars.goals.internal.service.FeasibilityService.Pace;
import io.github.thphuc06.jars.goals.internal.service.GoalAnalysis.Snapshot;
import io.github.thphuc06.jars.goals.internal.simulation.DeterministicProjection;
import io.github.thphuc06.jars.goals.internal.simulation.MonteCarloProjection;
import io.github.thphuc06.jars.goals.internal.simulation.ProjectionStrategy;
import io.github.thphuc06.jars.goals.internal.simulation.ProjectionStrategy.PathPoint;
import io.github.thphuc06.jars.goals.internal.simulation.ProjectionStrategy.ProjectionInput;
import io.github.thphuc06.jars.goals.internal.simulation.ProjectionStrategy.ProjectionOutcome;
import io.github.thphuc06.jars.goals.internal.simulation.StressProjection;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * What-if for a goal: how do the outcomes change if the user saves more, earns more or spends less? The base case and
 * every scenario run through the same three projection strategies (a straight line, a stress case and a Monte Carlo
 * simulation) with the same seed, so the difference between a scenario and the base is not blurred by random noise.
 *
 * <p>A request names its own changes. A request without any change gets the three default scenarios of the legacy
 * {@code scenario.py}: spend 15% less, earn 10% more, and earn 5% more while spending 10% less (percentages of the
 * user's observed averages).
 */
@Service
public class ScenarioService {

    public static final int MAX_HORIZON_MONTHS = 120;

    /**
     * What the user wants to change; a null field means no change.
     *
     * @param monthlySavingDelta extra money set aside for the goal every month (negative: less)
     * @param incomeDelta change of the monthly income
     * @param spendingDelta change of the monthly spending; negative means spending less
     * @param oneOffContribution an amount added to the goal now
     * @param horizonMonths how far to look ahead; default: the months until the target date (at least 1)
     * @param seed seed of the simulation; default: derived from the goal, so the answer is reproducible
     */
    public record ScenarioCommand(
            Money monthlySavingDelta,
            Money incomeDelta,
            Money spendingDelta,
            Money oneOffContribution,
            Integer horizonMonths,
            Long seed) {}

    public record Deltas(Money monthlySaving, Money income, Money spending, Money oneOff) {}

    public record PointView(int month, Money p10, Money p50, Money p90) {}

    /**
     * @param path the month-by-month p10/p50/p90, only for the Monte Carlo strategy (the others answer with the end
     *     amount alone)
     */
    public record ProjectionSummary(
            String strategy,
            Money p10,
            Money p50,
            Money p90,
            BigDecimal probabilityOfSuccess,
            boolean reachesTarget,
            List<PointView> path) {}

    /**
     * @param monthlyNet the expected monthly net in this scenario (the base pace plus the changes)
     * @param medianVsBase Monte Carlo median at the end minus the base's; zero for the base itself
     * @param probabilityVsBase Monte Carlo probability of success minus the base's; zero for the base itself
     */
    public record ScenarioResult(
            String name,
            Deltas deltas,
            Money monthlyNet,
            ProjectionSummary deterministic,
            ProjectionSummary stress,
            ProjectionSummary monteCarlo,
            Money medianVsBase,
            BigDecimal probabilityVsBase) {}

    public record ScenarioView(
            UUID goalId,
            long seed,
            int horizonMonths,
            Money startingAmount,
            Money targetAmount,
            Pace pace,
            ScenarioResult base,
            List<ScenarioResult> scenarios,
            List<String> notes) {}

    private static final BigDecimal CUT_SPENDING = new BigDecimal("0.15");
    private static final BigDecimal RAISE_INCOME = new BigDecimal("0.10");
    private static final BigDecimal BALANCED_INCOME = new BigDecimal("0.05");
    private static final BigDecimal BALANCED_SPENDING = new BigDecimal("0.10");

    private final GoalAnalysis analysis;
    private final ProjectionStrategy deterministic = new DeterministicProjection();
    private final ProjectionStrategy stress = new StressProjection();
    private final ProjectionStrategy monteCarlo = new MonteCarloProjection();

    ScenarioService(GoalAnalysis analysis) {
        this.analysis = analysis;
    }

    public ScenarioView scenarios(UserId userId, UUID goalId, ScenarioCommand cmd) {
        Snapshot s = analysis.snapshot(userId, goalId);
        CashflowStats stats = s.cashflow();
        if (cmd.horizonMonths() != null && (cmd.horizonMonths() < 1 || cmd.horizonMonths() > MAX_HORIZON_MONTHS)) {
            throw new DomainException(
                    GoalProblems.INVALID_GOAL, "horizonMonths phải từ 1 đến " + MAX_HORIZON_MONTHS + ".");
        }
        int untilTarget = Math.max(
                1, FeasibilityCalculator.monthsUntil(s.today(), s.goal().getTargetDate()));
        int months = cmd.horizonMonths() != null ? cmd.horizonMonths() : Math.min(untilTarget, MAX_HORIZON_MONTHS);
        long seed = cmd.seed() != null ? cmd.seed() : GoalAnalysis.seedOf(s.goalId());
        BigDecimal baseNet = stats.hasData() ? stats.averageNet() : BigDecimal.ZERO;
        BigDecimal volatility = stats.volatility();

        Deltas none = deltas(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        ScenarioResult base = run("BASE", none, s, months, baseNet, volatility, seed, null);

        List<ScenarioResult> results = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (cmd.horizonMonths() == null && untilTarget > MAX_HORIZON_MONTHS) {
            notes.add("Thời hạn dài hơn " + MAX_HORIZON_MONTHS + " tháng nên mô phỏng chỉ nhìn " + MAX_HORIZON_MONTHS
                    + " tháng đầu.");
        }
        if (isCustom(cmd)) {
            Deltas custom = deltas(
                    value(cmd.monthlySavingDelta()),
                    value(cmd.incomeDelta()),
                    value(cmd.spendingDelta()),
                    value(cmd.oneOffContribution()));
            results.add(run("CUSTOM", custom, s, months, baseNet, volatility, seed, base));
        } else if (stats.hasData()) {
            for (Preset preset : presets(stats)) {
                results.add(run(preset.name(), preset.deltas(), s, months, baseNet, volatility, seed, base));
            }
        } else {
            notes.add("Chưa có lịch sử thu chi nên chưa có kịch bản mặc định; hãy nhập mức thay đổi bạn muốn thử.");
        }
        if (!stats.hasData()) {
            notes.add("Chưa có lịch sử thu chi: tốc độ gốc coi là 0 và kết quả chỉ dựa vào các thay đổi bạn nhập.");
        }
        return new ScenarioView(
                s.goalId(),
                seed,
                months,
                s.currentAmount(),
                s.target(),
                Pace.of(stats),
                base,
                List.copyOf(results),
                List.copyOf(notes));
    }

    // ------------------------------------------------------------------ one scenario

    private ScenarioResult run(
            String name,
            Deltas deltas,
            Snapshot s,
            int months,
            BigDecimal baseNet,
            BigDecimal volatility,
            long seed,
            ScenarioResult base) {
        BigDecimal netChange = deltas.monthlySaving()
                .amount()
                .add(deltas.income().amount())
                .subtract(deltas.spending().amount());
        BigDecimal monthlyNet = baseNet.add(netChange);
        ProjectionInput input = new ProjectionInput(
                s.currentAmount().amount().add(deltas.oneOff().amount()),
                s.target().amount(),
                months,
                monthlyNet,
                volatility,
                seed);

        ProjectionOutcome line = deterministic.project(input);
        ProjectionOutcome stressed = stress.project(input);
        ProjectionOutcome simulated = monteCarlo.project(input);

        ProjectionSummary mc = summary(simulated, true);
        Money medianVsBase = base == null
                ? Money.zero(Money.VND)
                : mc.p50().minus(base.monteCarlo().p50());
        BigDecimal probabilityVsBase = base == null
                ? BigDecimal.ZERO.setScale(4)
                : mc.probabilityOfSuccess().subtract(base.monteCarlo().probabilityOfSuccess());
        return new ScenarioResult(
                name,
                deltas,
                new Money(monthlyNet.setScale(2, RoundingMode.HALF_EVEN), Money.VND),
                summary(line, false),
                summary(stressed, false),
                mc,
                medianVsBase,
                probabilityVsBase);
    }

    private static ProjectionSummary summary(ProjectionOutcome o, boolean withPath) {
        List<PointView> path =
                withPath ? o.path().stream().map(ScenarioService::point).toList() : List.of();
        return new ProjectionSummary(
                o.strategy(),
                new Money(o.p10(), Money.VND),
                new Money(o.p50(), Money.VND),
                new Money(o.p90(), Money.VND),
                o.probabilityOfSuccess(),
                o.reachesTarget(),
                path);
    }

    private static PointView point(PathPoint p) {
        return new PointView(
                p.month(), new Money(p.p10(), Money.VND), new Money(p.p50(), Money.VND), new Money(p.p90(), Money.VND));
    }

    // ------------------------------------------------------------------ what to try

    private static boolean isCustom(ScenarioCommand cmd) {
        return value(cmd.monthlySavingDelta()).signum() != 0
                || value(cmd.incomeDelta()).signum() != 0
                || value(cmd.spendingDelta()).signum() != 0
                || value(cmd.oneOffContribution()).signum() != 0;
    }

    private record Preset(String name, Deltas deltas) {}

    /** The three scenarios of the legacy {@code _default_variants}, as percentages of the observed averages. */
    private static List<Preset> presets(CashflowStats stats) {
        BigDecimal income = stats.averageIncome();
        BigDecimal expense = stats.averageExpense();
        BigDecimal zero = BigDecimal.ZERO;
        return List.of(
                new Preset(
                        "CUT_SPENDING_15PCT",
                        deltas(zero, zero, expense.multiply(CUT_SPENDING).negate(), zero)),
                new Preset("INCOME_UP_10PCT", deltas(zero, income.multiply(RAISE_INCOME), zero, zero)),
                new Preset(
                        "INCOME_UP_5PCT_SPENDING_DOWN_10PCT",
                        deltas(
                                zero,
                                income.multiply(BALANCED_INCOME),
                                expense.multiply(BALANCED_SPENDING).negate(),
                                zero)));
    }

    private static Deltas deltas(BigDecimal saving, BigDecimal income, BigDecimal spending, BigDecimal oneOff) {
        return new Deltas(money(saving), money(income), money(spending), money(oneOff));
    }

    private static Money money(BigDecimal amount) {
        return new Money(amount.setScale(2, RoundingMode.HALF_EVEN), Money.VND);
    }

    private static BigDecimal value(Money money) {
        return money == null ? BigDecimal.ZERO : money.amount();
    }
}
