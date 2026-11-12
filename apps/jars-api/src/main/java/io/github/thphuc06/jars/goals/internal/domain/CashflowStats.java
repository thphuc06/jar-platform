package io.github.thphuc06.jars.goals.internal.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

/**
 * What the recent months say about the user's monthly saving pace: the average income, spending and net, and how much
 * the net varies from month to month.
 *
 * <p>The running month is partial (the salary may not have arrived, the rent may not be paid), so it is left out as long
 * as there are complete months to look at; if it is all there is, it is used and {@code currentMonthOnly} says so. At most
 * the last {@link #MAX_MONTHS} months count: older ones say little about the pace now.
 *
 * @param months how many months the numbers are based on; 0 means there is no history, and then every amount is zero
 * @param netStdDev sample standard deviation of the monthly net, or null with fewer than two months
 */
public record CashflowStats(
        int months,
        boolean currentMonthOnly,
        BigDecimal averageIncome,
        BigDecimal averageExpense,
        BigDecimal averageNet,
        BigDecimal netStdDev) {

    public static final int MAX_MONTHS = 6;

    public static final BigDecimal MIN_VOLATILITY = BigDecimal.ONE;
    private static final BigDecimal SINGLE_MONTH_SPREAD = new BigDecimal("0.20");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** Income and spending of one month. */
    public record MonthFlow(YearMonth month, BigDecimal income, BigDecimal expense) {
        BigDecimal net() {
            return income.subtract(expense);
        }
    }

    /**
     * How much the monthly net is expected to vary, for the simulation: the standard deviation of the months, or with a
     * single month 20% of its size (one month says nothing about spread), never below {@link #MIN_VOLATILITY} so the
     * simulation is never degenerate (legacy {@code max(1.0, ...)}). Zero without history.
     */
    public BigDecimal volatility() {
        if (!hasData()) {
            return ZERO;
        }
        BigDecimal spread = netStdDev != null ? netStdDev : averageNet.abs().multiply(SINGLE_MONTH_SPREAD);
        return spread.max(MIN_VOLATILITY).setScale(2, RoundingMode.HALF_EVEN);
    }

    public static CashflowStats none() {
        return new CashflowStats(0, false, ZERO, ZERO, ZERO, null);
    }

    public boolean hasData() {
        return months > 0;
    }

    /**
     * @param flows the months that have any activity, in any order
     * @param current the running month (Vietnam time)
     */
    public static CashflowStats of(List<MonthFlow> flows, YearMonth current) {
        List<MonthFlow> complete = flows.stream()
                .filter(f -> !f.month().equals(current))
                .sorted(Comparator.comparing(MonthFlow::month))
                .toList();
        boolean currentOnly = complete.isEmpty() && !flows.isEmpty();
        List<MonthFlow> used = currentOnly ? flows : complete;
        if (used.isEmpty()) {
            return none();
        }
        if (used.size() > MAX_MONTHS) {
            used = used.subList(used.size() - MAX_MONTHS, used.size());
        }

        int n = used.size();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (MonthFlow f : used) {
            income = income.add(f.income());
            expense = expense.add(f.expense());
        }
        BigDecimal count = BigDecimal.valueOf(n);
        BigDecimal meanNet = income.subtract(expense).divide(count, 20, RoundingMode.HALF_EVEN);
        BigDecimal stdDev = null;
        if (n >= 2) {
            BigDecimal squares = BigDecimal.ZERO;
            for (MonthFlow f : used) {
                BigDecimal deviation = f.net().subtract(meanNet);
                squares = squares.add(deviation.multiply(deviation));
            }
            stdDev = squares.divide(BigDecimal.valueOf(n - 1L), 20, RoundingMode.HALF_EVEN)
                    .sqrt(MathContext.DECIMAL64)
                    .setScale(2, RoundingMode.HALF_EVEN);
        }
        return new CashflowStats(
                n,
                currentOnly,
                income.divide(count, 2, RoundingMode.HALF_EVEN),
                expense.divide(count, 2, RoundingMode.HALF_EVEN),
                meanNet.setScale(2, RoundingMode.HALF_EVEN),
                stdDev);
    }
}
