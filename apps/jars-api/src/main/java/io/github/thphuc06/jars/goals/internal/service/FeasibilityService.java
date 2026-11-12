package io.github.thphuc06.jars.goals.internal.service;

import io.github.thphuc06.jars.goals.internal.domain.CashflowStats;
import io.github.thphuc06.jars.goals.internal.domain.FeasibilityCalculator;
import io.github.thphuc06.jars.goals.internal.domain.FeasibilityCalculator.Verdict;
import io.github.thphuc06.jars.goals.internal.service.GoalAnalysis.Snapshot;
import io.github.thphuc06.jars.goals.internal.simulation.MonteCarloProjection;
import io.github.thphuc06.jars.goals.internal.simulation.ProjectionStrategy.ProjectionInput;
import io.github.thphuc06.jars.goals.internal.simulation.ProjectionStrategy.ProjectionOutcome;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Can the target be reached by the date at the current saving pace, and how much per month does it take? The answer
 * has a deterministic part ({@link FeasibilityCalculator}: what is missing, what is needed per month, where the pace
 * leads) and a probabilistic one (a seeded Monte Carlo over the spread of the user's past months).
 */
@Service
public class FeasibilityService {

    /** The pace the answer is based on, as measured over the recent complete months. */
    public record Pace(
            int monthsUsed,
            boolean currentMonthOnly,
            Money averageIncome,
            Money averageExpense,
            Money averageNet,
            Money netStdDev) {
        static Pace of(CashflowStats stats) {
            return new Pace(
                    stats.months(),
                    stats.currentMonthOnly(),
                    Money.vnd(stats.averageIncome().toPlainString()),
                    Money.vnd(stats.averageExpense().toPlainString()),
                    Money.vnd(stats.averageNet().toPlainString()),
                    stats.netStdDev() == null
                            ? null
                            : Money.vnd(stats.netStdDev().toPlainString()));
        }
    }

    /**
     * @param verdict {@code ACHIEVED}, {@code ON_TRACK}, {@code OFF_TRACK} or {@code NO_DATA}
     * @param fundingJarLinked false when the goal has no active funding jar: the current amount is then 0
     * @param monthsLeft whole months until the target date (a started month counts); 0 once the date has come
     * @param requiredMonthly what has to be saved every month from now
     * @param currentMonthlyPace the average monthly net of the user; null without history
     * @param projectedAtDeadline where that pace leads at the target date; null without history
     * @param gap what is still missing at the target date at that pace; null without history
     * @param monthlyShortfall how much more per month is needed than the pace gives; null without history
     * @param probabilityOfSuccess share of 2,000 simulated futures that reach the target by the date, 0 to 1; null
     *     without history
     * @param grade {@code A} from 75%, {@code B} from 45%, otherwise {@code C}; null without history
     * @param feasibilityHint {@code high}, {@code medium}, {@code stretch}, {@code low} or {@code unknown}
     * @param seed the seed of the simulation, so the same answer can be reproduced
     */
    public record FeasibilityView(
            UUID goalId,
            String goalName,
            String verdict,
            Money targetAmount,
            LocalDate targetDate,
            Money currentAmount,
            boolean fundingJarLinked,
            int monthsLeft,
            Money remaining,
            Money requiredMonthly,
            Money currentMonthlyPace,
            Money projectedAtDeadline,
            Money gap,
            Money monthlyShortfall,
            BigDecimal probabilityOfSuccess,
            String grade,
            String feasibilityHint,
            Pace pace,
            long seed,
            List<String> notes) {}

    /** What the roadmap needs from a feasibility check without the response shape. */
    record Assessment(FeasibilityCalculator.Result result, BigDecimal probability, String grade, String hint) {}

    private final GoalAnalysis analysis;
    private final MonteCarloProjection simulation = new MonteCarloProjection();

    FeasibilityService(GoalAnalysis analysis) {
        this.analysis = analysis;
    }

    public FeasibilityView feasibility(UserId userId, UUID goalId) {
        Snapshot s = analysis.snapshot(userId, goalId);
        Assessment a = assess(s);
        FeasibilityCalculator.Result r = a.result();
        CashflowStats stats = s.cashflow();
        return new FeasibilityView(
                s.goalId(),
                s.goal().getName(),
                r.verdict().name(),
                s.target(),
                s.goal().getTargetDate(),
                s.currentAmount(),
                s.fundingJarActive(),
                r.monthsLeft(),
                money(r.remaining()),
                money(r.requiredMonthly()),
                stats.hasData() ? money(stats.averageNet()) : null,
                money(r.projectedAtDeadline()),
                money(r.gap()),
                money(r.monthlyShortfall()),
                a.probability(),
                a.grade(),
                a.hint(),
                Pace.of(stats),
                GoalAnalysis.seedOf(s.goalId()),
                notes(s, r));
    }

    /** Deterministic result plus the simulated probability, with the default seed of the goal. */
    Assessment assess(Snapshot s) {
        CashflowStats stats = s.cashflow();
        FeasibilityCalculator.Result r = FeasibilityCalculator.evaluate(
                s.currentAmount().amount(),
                s.target().amount(),
                s.today(),
                s.goal().getTargetDate(),
                stats.hasData() ? stats.averageNet() : null);

        BigDecimal probability;
        if (r.verdict() == Verdict.ACHIEVED) {
            probability = BigDecimal.ONE.setScale(4);
        } else if (r.verdict() == Verdict.NO_DATA) {
            probability = null;
        } else {
            ProjectionOutcome outcome = simulation.project(new ProjectionInput(
                    s.currentAmount().amount(),
                    s.target().amount(),
                    Math.max(1, r.monthsLeft()),
                    stats.averageNet(),
                    stats.volatility(),
                    GoalAnalysis.seedOf(s.goalId())));
            probability = outcome.probabilityOfSuccess();
        }
        String grade = probability == null ? null : FeasibilityCalculator.grade(probability);
        String hint = probability == null ? "unknown" : FeasibilityCalculator.hint(probability, r.gap());
        return new Assessment(r, probability, grade, hint);
    }

    private static List<String> notes(Snapshot s, FeasibilityCalculator.Result r) {
        List<String> notes = new ArrayList<>();
        if (s.goal().getFundingJarId() == null) {
            notes.add("Mục tiêu chưa gắn hũ nào nên số tiền đã có được tính là 0.");
        } else if (!s.fundingJarActive()) {
            notes.add("Hũ gắn với mục tiêu đã được lưu trữ nên không tính số tiền trong hũ.");
        }
        if (r.verdict() == Verdict.NO_DATA) {
            notes.add("Chưa có lịch sử thu chi nên chưa đánh giá được tốc độ tiết kiệm.");
        } else if (s.cashflow().currentMonthOnly()) {
            notes.add("Mới có dữ liệu của tháng đang chạy nên tốc độ có thể chưa chính xác.");
        } else if (s.cashflow().months() < 3) {
            notes.add("Mới có " + s.cashflow().months() + " tháng dữ liệu nên xác suất còn nhiều bất định.");
        }
        if (r.monthsLeft() == 0 && r.verdict() != Verdict.ACHIEVED) {
            notes.add("Đã đến hoặc quá ngày đạt mục tiêu: số tiền cần mỗi tháng tính cho một tháng.");
        }
        return List.copyOf(notes);
    }

    private static Money money(BigDecimal amount) {
        return amount == null ? null : new Money(amount, Money.VND);
    }
}
