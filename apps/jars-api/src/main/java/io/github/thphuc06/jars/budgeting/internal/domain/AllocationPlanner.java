package io.github.thphuc06.jars.budgeting.internal.domain;

import io.github.thphuc06.jars.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Suggests how to divide an income over the user's jars. A port of the idea of the hackathon's
 * {@code planner_agent/finance/allocation.py}, deliberately much smaller:
 *
 * <p><b>Kept</b>
 *
 * <ul>
 *   <li>Allocation follows what the user plans for each jar and is nudged by what they really spend (the legacy code
 *       sized the "bills" and "living" jars from their average monthly spend).
 *   <li>The confidence in the spending history grows with the number of months of history: the legacy
 *       {@code spend_coverage_quality = min(months, 6) / 6} becomes the weight of the nudge.
 *   <li>The result is normalised so the shares add up to exactly 100% and the amounts to exactly the income, with
 *       nothing left over and nothing allocated beyond the income (legacy {@code leftover = 0} and
 *       {@code allocation_clipped_to_income}).
 * </ul>
 *
 * <p><b>Dropped</b>
 *
 * <ul>
 *   <li>Inferring a "role" (bills, emergency, goals, living, discretionary) from jar names and category keywords: the
 *       jars here are the six fixed jars of the 6-jars method plus custom ones, and the user already sets each jar's
 *       {@code targetPct}.
 *   <li>The fixed waterfall (bills first, then 20% emergency, then goals, then living, the rest as discretionary): it
 *       overrides the percentages the user chose.
 *   <li>Monthly income taken from history (the caller passes the amount to allocate), goal needs, transfer suggestions
 *       between jars, forecast intervals, risk and trust scores, and everything that needed Supabase or a model.
 * </ul>
 */
public final class AllocationPlanner {

    /** Largest share of the final weight that the spending history may take over from the planned percentage. */
    public static final BigDecimal MAX_HISTORY_WEIGHT = new BigDecimal("0.20");

    /** From this many months of history on, the history counts with its full {@link #MAX_HISTORY_WEIGHT}. */
    public static final int FULL_HISTORY_MONTHS = 6;

    private static final int SCALE = 12;
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private AllocationPlanner() {}

    /**
     * @param targetPct what the user plans for the jar, 0 to 100 (the jars' percentages need not add up to 100)
     * @param averageMonthlySpend what the jar spent per month in the months with history, never negative
     */
    public record JarProfile(UUID jarId, BigDecimal targetPct, BigDecimal averageMonthlySpend) {}

    /** One jar's part of the income: its planned share, the share suggested, and the amount. */
    public record Share(UUID jarId, BigDecimal targetPct, BigDecimal sharePct, Money amount) {}

    /**
     * @param historyWeight how much of the weights came from spending history instead of the planned percentages, 0 to
     *     {@link #MAX_HISTORY_WEIGHT}
     */
    public record Plan(List<Share> shares, int monthsOfHistory, BigDecimal historyWeight) {}

    /**
     * Rules, in order:
     *
     * <ol>
     *   <li>Start from each jar's {@code targetPct}. If no jar has a percentage, divide equally.
     *   <li>With {@code monthsOfHistory > 0} and some spending, blend in each jar's share of the average spending:
     *       {@code weight = (1 - a) * planned + a * spent}, {@code a = 0.20 * min(months, 6) / 6}. A jar planned at 0%
     *       stays at 0%: history nudges the user's plan, it does not invent jars to fund.
     *   <li>Normalise: the shares are divided by their sum, then rounded with the largest-remainder method so the
     *       percentages add up to exactly 100.00 and the amounts to exactly {@code income}.
     * </ol>
     */
    public static Plan plan(Money income, List<JarProfile> jars, int monthsOfHistory) {
        if (jars.isEmpty()) {
            throw new IllegalArgumentException("At least one jar is needed");
        }
        List<BigDecimal> planned = plannedShares(jars);
        BigDecimal historyWeight = historyWeight(monthsOfHistory);
        List<BigDecimal> weights = planned;
        BigDecimal appliedWeight = BigDecimal.ZERO.setScale(4);

        BigDecimal spendTotal = BigDecimal.ZERO;
        for (int i = 0; i < jars.size(); i++) {
            if (planned.get(i).signum() > 0) {
                spendTotal = spendTotal.add(jars.get(i).averageMonthlySpend());
            }
        }
        if (historyWeight.signum() > 0 && spendTotal.signum() > 0) {
            List<BigDecimal> blended = new ArrayList<>();
            for (int i = 0; i < jars.size(); i++) {
                if (planned.get(i).signum() == 0) {
                    blended.add(BigDecimal.ZERO);
                    continue;
                }
                BigDecimal spentShare =
                        jars.get(i).averageMonthlySpend().divide(spendTotal, SCALE, RoundingMode.HALF_EVEN);
                blended.add(BigDecimal.ONE
                        .subtract(historyWeight)
                        .multiply(planned.get(i))
                        .add(historyWeight.multiply(spentShare)));
            }
            weights = blended;
            appliedWeight = historyWeight.setScale(4, RoundingMode.HALF_EVEN);
        }

        List<BigDecimal> percentages = LargestRemainder.apportion(HUNDRED, 2, weights);
        List<BigDecimal> amounts = LargestRemainder.apportion(income.amount(), 2, weights);
        List<Share> shares = new ArrayList<>();
        for (int i = 0; i < jars.size(); i++) {
            shares.add(new Share(
                    jars.get(i).jarId(),
                    jars.get(i).targetPct(),
                    percentages.get(i),
                    new Money(amounts.get(i), income.currency())));
        }
        return new Plan(List.copyOf(shares), monthsOfHistory, appliedWeight);
    }

    /** Each jar's planned percentage as a fraction of the total (an equal split when nothing is planned). */
    private static List<BigDecimal> plannedShares(List<JarProfile> jars) {
        BigDecimal total = BigDecimal.ZERO;
        for (JarProfile jar : jars) {
            total = total.add(jar.targetPct());
        }
        List<BigDecimal> shares = new ArrayList<>();
        for (JarProfile jar : jars) {
            shares.add(
                    total.signum() == 0
                            ? BigDecimal.ONE.divide(BigDecimal.valueOf(jars.size()), SCALE, RoundingMode.HALF_EVEN)
                            : jar.targetPct().divide(total, SCALE, RoundingMode.HALF_EVEN));
        }
        return shares;
    }

    private static BigDecimal historyWeight(int monthsOfHistory) {
        int months = Math.max(0, Math.min(monthsOfHistory, FULL_HISTORY_MONTHS));
        return MAX_HISTORY_WEIGHT
                .multiply(BigDecimal.valueOf(months))
                .divide(BigDecimal.valueOf(FULL_HISTORY_MONTHS), SCALE, RoundingMode.HALF_EVEN);
    }
}
