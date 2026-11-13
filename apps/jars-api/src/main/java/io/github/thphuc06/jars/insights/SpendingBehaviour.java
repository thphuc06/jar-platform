package io.github.thphuc06.jars.insights;

import io.github.thphuc06.jars.shared.Money;
import java.time.YearMonth;
import java.util.List;

/**
 * This month's spending per category next to the user's own usual monthly level.
 *
 * <p>The current month is partial: {@code dayOfMonth} of {@code daysInMonth} days have passed, and {@code spent} is
 * compared with <em>whole</em> past months. Nothing is scaled up to a full month, because spending is not evenly
 * spread (rent, bills); the caller decides how to word that.
 *
 * @param baselineMonths how many of the {@link InsightsApi#BASELINE_MONTHS} calendar months before this one have any
 *     spending recorded (a month without any is a month the user was not using the app, not a month of zero spending)
 * @param categories categories with spending this month, largest first, at most {@link InsightsApi#MAX_CATEGORIES}
 */
public record SpendingBehaviour(
        YearMonth month, int dayOfMonth, int daysInMonth, int baselineMonths, List<CategoryBehaviour> categories) {

    /** Whether there is enough history to speak of a "usual" level: at least {@link InsightsApi#MIN_BASELINE_MONTHS}. */
    public boolean comparable() {
        return baselineMonths >= InsightsApi.MIN_BASELINE_MONTHS;
    }

    /**
     * @param category the category name ("Chưa phân loại" for expenses without one)
     * @param spent spending in the current month so far
     * @param usual the average monthly spending in the baseline months, whole VND; zero for a category with no history
     *     in them, {@code null} when the user is not {@link #comparable()}
     * @param difference {@code spent - usual}; {@code null} exactly when {@code usual} is
     * @param changePercent {@code (spent - usual) / usual} in whole percent; {@code null} when {@code usual} is
     *     {@code null} or zero (a new category has no percentage)
     */
    public record CategoryBehaviour(
            String category, Money spent, Money usual, Money difference, Integer changePercent) {}
}
