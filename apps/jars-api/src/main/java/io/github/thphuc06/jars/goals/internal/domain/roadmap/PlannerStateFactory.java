package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import io.github.thphuc06.jars.goals.internal.domain.CashflowStats;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PlannerState;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.SavingsCapacity;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns the numbers this system has (recent cashflow, jar balances, open anomalies, the feasibility result) into the
 * labelled "planner state" the roadmap engines reason about. The legacy backend got these labels from a planner agent's
 * report; the thresholds that are known from it are kept (capacity labels in VND, runway bands in months), the rest are
 * defined here and documented on each method.
 *
 * <p>Whatever cannot be known from the history is left {@code null} or {@code "unknown"}, and the readiness classifier
 * then reports it as a missing field instead of guessing.
 */
public final class PlannerStateFactory {

    public static final String UNKNOWN = "unknown";
    static final BigDecimal LOW_CAPACITY_BELOW = new BigDecimal("2000000");
    static final BigDecimal MODERATE_CAPACITY_BELOW = new BigDecimal("8000000");
    private static final BigDecimal ONE_MONTH = BigDecimal.ONE;
    private static final BigDecimal THREE_MONTHS = BigDecimal.valueOf(3);
    private static final BigDecimal SIX_MONTHS = BigDecimal.valueOf(6);

    private PlannerStateFactory() {}

    /**
     * @param stats the recent months of cashflow
     * @param totalBalance what all the active jars hold together; the buffer the runway is measured against
     * @param fundingAmount what the funding jar of the goal holds (0 when the goal has none)
     * @param target the goal's target amount
     * @param openAnomalies anomalies the user has not answered yet
     * @param feasibilityHint {@code high}, {@code medium}, {@code stretch}, {@code low} or {@code unknown}
     */
    public static PlannerState build(
            CashflowStats stats,
            BigDecimal totalBalance,
            BigDecimal fundingAmount,
            BigDecimal target,
            long openAnomalies,
            String feasibilityHint) {
        BigDecimal capacity = stats.hasData() ? stats.averageNet() : null;
        BigDecimal runway = runwayMonths(stats, totalBalance);
        boolean anomalyActive = openAnomalies > 0;
        return new PlannerState(
                cashflowStatus(capacity),
                new SavingsCapacity(capacity, capacityLabel(capacity)),
                runway,
                liquidityPressure(runway),
                anomalyActive ? "active" : "none",
                readinessLabel(capacity, runway, anomalyActive),
                feasibilityHint == null ? UNKNOWN : feasibilityHint,
                riskBand(capacity, runway, anomalyActive),
                progressRatio(fundingAmount, target),
                totalBalance.signum() > 0 ? "present" : "missing",
                "jars_ledger");
    }

    /** {@code positive}, {@code negative}, {@code flat} (exactly zero) or {@code unknown} without history. */
    static String cashflowStatus(BigDecimal capacity) {
        if (capacity == null) {
            return UNKNOWN;
        }
        return capacity.signum() > 0 ? "positive" : capacity.signum() < 0 ? "negative" : "flat";
    }

    /** Legacy {@code _label_for_capacity}: none up to 0, low below 2,000,000, moderate below 8,000,000, else strong. */
    static String capacityLabel(BigDecimal monthly) {
        if (monthly == null) {
            return UNKNOWN;
        }
        if (monthly.signum() <= 0) {
            return "none";
        }
        if (monthly.compareTo(LOW_CAPACITY_BELOW) < 0) {
            return "low";
        }
        return monthly.compareTo(MODERATE_CAPACITY_BELOW) < 0 ? "moderate" : "strong";
    }

    /**
     * How many months the money in the jars lasts at the average monthly spending; null when nothing was spent in the
     * history (no way to tell). An overdrawn total counts as zero months.
     */
    static BigDecimal runwayMonths(CashflowStats stats, BigDecimal totalBalance) {
        if (!stats.hasData() || stats.averageExpense().signum() <= 0) {
            return null;
        }
        return totalBalance.max(BigDecimal.ZERO).divide(stats.averageExpense(), 2, RoundingMode.HALF_EVEN);
    }

    /** Legacy {@code _liquidity_pressure}: critical under 1 month of runway, high under 3, medium under 6, else low. */
    static String liquidityPressure(BigDecimal runway) {
        if (runway == null) {
            return UNKNOWN;
        }
        if (runway.compareTo(ONE_MONTH) < 0) {
            return "critical";
        }
        if (runway.compareTo(THREE_MONTHS) < 0) {
            return "high";
        }
        return runway.compareTo(SIX_MONTHS) < 0 ? "medium" : "low";
    }

    /**
     * Counts the problems among: the pace does not leave a surplus, the runway is under 3 months, an anomaly is open.
     * None is {@code ready}, one is {@code cautious}, two or more {@code not_ready}. Unknown while the pace or the runway
     * is unknown.
     */
    static String readinessLabel(BigDecimal capacity, BigDecimal runway, boolean anomalyActive) {
        if (capacity == null || runway == null) {
            return UNKNOWN;
        }
        int problems = 0;
        if (capacity.signum() <= 0) {
            problems++;
        }
        if (runway.compareTo(THREE_MONTHS) < 0) {
            problems++;
        }
        if (anomalyActive) {
            problems++;
        }
        return problems == 0 ? "ready" : problems == 1 ? "cautious" : "not_ready";
    }

    /**
     * {@code critical} under 1 month of runway, or a negative pace with under 3; {@code high} for a negative pace or
     * under 3 months; {@code moderate} under 6 months or with an open anomaly; otherwise {@code low}.
     */
    static String riskBand(BigDecimal capacity, BigDecimal runway, boolean anomalyActive) {
        if (capacity == null || runway == null) {
            return UNKNOWN;
        }
        boolean negative = capacity.signum() < 0;
        if (runway.compareTo(ONE_MONTH) < 0 || (negative && runway.compareTo(THREE_MONTHS) < 0)) {
            return "critical";
        }
        if (negative || runway.compareTo(THREE_MONTHS) < 0) {
            return "high";
        }
        return runway.compareTo(SIX_MONTHS) < 0 || anomalyActive ? "moderate" : "low";
    }

    /** What the funding jar holds as a share of the target, from 0 to 1 with 5 decimals. */
    static BigDecimal progressRatio(BigDecimal fundingAmount, BigDecimal target) {
        if (target.signum() <= 0) {
            return BigDecimal.ZERO.setScale(5);
        }
        return fundingAmount
                .max(BigDecimal.ZERO)
                .divide(target, 5, RoundingMode.HALF_EVEN)
                .min(BigDecimal.ONE);
    }
}
