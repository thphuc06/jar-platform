package io.github.thphuc06.jars.goals.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;

/**
 * The deterministic part of "can the target be reached by the date at the current pace": how much is still missing, how
 * much has to be saved per month, and where the current pace leads. The probabilistic part (a Monte Carlo over the
 * spread of past months) lives in the {@code simulation} package.
 *
 * <p>The pace is the average monthly net cashflow of the user (all income minus all spending), so with several goals the
 * same surplus is counted for each of them; compare their required amounts before promising it twice.
 */
public final class FeasibilityCalculator {

    private FeasibilityCalculator() {}

    /**
     * @param ACHIEVED the jar already holds the target
     * @param ON_TRACK the current pace reaches the target by the date
     * @param OFF_TRACK it does not
     * @param NO_DATA there is no history to say anything about the pace
     */
    public enum Verdict {
        ACHIEVED,
        ON_TRACK,
        OFF_TRACK,
        NO_DATA
    }

    /**
     * @param monthsLeft whole months until the date, counting a started month as a month; 0 once the date has come
     * @param requiredMonthly what must be saved every month (rounded up to the cent); with no month left it is the
     *     whole remaining amount
     * @param projectedAtDeadline current amount plus pace times months, never below 0; null without a pace
     * @param gap what the pace still misses at the deadline; null without a pace
     * @param monthlyShortfall how much more per month is needed than the pace gives (negative pace counts in full); null
     *     without a pace
     */
    public record Result(
            Verdict verdict,
            int monthsLeft,
            BigDecimal remaining,
            BigDecimal requiredMonthly,
            BigDecimal projectedAtDeadline,
            BigDecimal gap,
            BigDecimal monthlyShortfall) {}

    /** Months from {@code today} to {@code targetDate}, rounding a started month up; never negative. */
    public static int monthsUntil(LocalDate today, LocalDate targetDate) {
        if (!targetDate.isAfter(today)) {
            return 0;
        }
        Period period = Period.between(today, targetDate);
        int months = Math.toIntExact(period.toTotalMonths());
        return period.getDays() > 0 ? months + 1 : months;
    }

    /**
     * @param averageNet the monthly pace, or null when there is no history
     */
    public static Result evaluate(
            BigDecimal current, BigDecimal target, LocalDate today, LocalDate targetDate, BigDecimal averageNet) {
        int monthsLeft = monthsUntil(today, targetDate);
        BigDecimal remaining = target.subtract(current).max(BigDecimal.ZERO).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal requiredMonthly = remaining
                .divide(BigDecimal.valueOf(Math.max(1, monthsLeft)), 2, RoundingMode.CEILING)
                .max(BigDecimal.ZERO);

        if (remaining.signum() == 0) {
            return new Result(
                    Verdict.ACHIEVED,
                    monthsLeft,
                    remaining,
                    requiredMonthly,
                    current.max(BigDecimal.ZERO),
                    BigDecimal.ZERO.setScale(2),
                    BigDecimal.ZERO.setScale(2));
        }
        if (averageNet == null) {
            return new Result(Verdict.NO_DATA, monthsLeft, remaining, requiredMonthly, null, null, null);
        }
        BigDecimal projected = current.add(averageNet.multiply(BigDecimal.valueOf(monthsLeft)))
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal gap = target.subtract(projected).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal shortfall =
                requiredMonthly.subtract(averageNet).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN);
        Verdict verdict = projected.compareTo(target) >= 0 ? Verdict.ON_TRACK : Verdict.OFF_TRACK;
        return new Result(verdict, monthsLeft, remaining, requiredMonthly, projected, gap, shortfall);
    }

    /** Legacy grade of {@code goal.py}: A from 75% probability of success, B from 45%, otherwise C. */
    public static String grade(BigDecimal probabilityOfSuccess) {
        if (probabilityOfSuccess.compareTo(new BigDecimal("0.75")) >= 0) {
            return "A";
        }
        return probabilityOfSuccess.compareTo(new BigDecimal("0.45")) >= 0 ? "B" : "C";
    }

    /**
     * Legacy {@code _feasibility_hint}: {@code high} at 75%, {@code medium} at 45%, {@code stretch} while something is
     * still missing at the deadline, otherwise {@code low}.
     */
    public static String hint(BigDecimal probabilityOfSuccess, BigDecimal gap) {
        if (probabilityOfSuccess.compareTo(new BigDecimal("0.75")) >= 0) {
            return "high";
        }
        if (probabilityOfSuccess.compareTo(new BigDecimal("0.45")) >= 0) {
            return "medium";
        }
        return gap != null && gap.signum() > 0 ? "stretch" : "low";
    }
}
