package io.github.thphuc06.jars.goals.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.goals.internal.domain.CashflowStats.MonthFlow;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CashflowStatsTest {

    private static final YearMonth NOW = YearMonth.of(2026, 11);

    private static MonthFlow flow(int year, int month, String income, String expense) {
        return new MonthFlow(YearMonth.of(year, month), new BigDecimal(income), new BigDecimal(expense));
    }

    @Test
    void theAverages_andTheSpreadOfTheCompleteMonths() {
        List<MonthFlow> flows = List.of(
                flow(2026, 8, "20000000", "12000000"), // net 8,000,000
                flow(2026, 9, "20000000", "11000000"), // net 9,000,000
                flow(2026, 10, "20000000", "10000000")); // net 10,000,000

        CashflowStats stats = CashflowStats.of(flows, NOW);

        assertThat(stats.months()).isEqualTo(3);
        assertThat(stats.currentMonthOnly()).isFalse();
        assertThat(stats.averageIncome()).isEqualByComparingTo("20000000.00");
        assertThat(stats.averageExpense()).isEqualByComparingTo("11000000.00");
        assertThat(stats.averageNet()).isEqualByComparingTo("9000000.00");
        assertThat(stats.netStdDev()).isEqualByComparingTo("1000000.00"); // sample standard deviation of 8, 9, 10
        assertThat(stats.volatility()).isEqualByComparingTo("1000000.00");
        assertThat(stats.hasData()).isTrue();
    }

    @Test
    void theSampleStandardDeviation_usesNMinusOne() {
        // nets 1, 3, 2, 4 million: mean 2.5M, sum of squares 5e12, variance 5e12 / 3, deviation 1,290,994.4487...
        List<MonthFlow> flows = List.of(
                flow(2026, 7, "1000000", "0"),
                flow(2026, 8, "3000000", "0"),
                flow(2026, 9, "2000000", "0"),
                flow(2026, 10, "4000000", "0"));

        CashflowStats stats = CashflowStats.of(flows, NOW);

        assertThat(stats.averageNet()).isEqualByComparingTo("2500000.00");
        assertThat(stats.netStdDev()).isEqualByComparingTo("1290994.45");
    }

    @Test
    void theRunningMonthIsLeftOut_whenThereAreCompleteMonths() {
        List<MonthFlow> flows = List.of(
                flow(2026, 10, "10000000", "4000000"), flow(2026, 11, "999", "0")); // November has just started

        CashflowStats stats = CashflowStats.of(flows, NOW);

        assertThat(stats.months()).isEqualTo(1);
        assertThat(stats.averageNet()).isEqualByComparingTo("6000000.00");
        assertThat(stats.currentMonthOnly()).isFalse();
    }

    @Test
    void whenTheRunningMonthIsAllThereIs_itIsUsedAndFlagged() {
        CashflowStats stats = CashflowStats.of(List.of(flow(2026, 11, "5000000", "0")), NOW);

        assertThat(stats.months()).isEqualTo(1);
        assertThat(stats.currentMonthOnly()).isTrue();
        assertThat(stats.averageNet()).isEqualByComparingTo("5000000.00");
        assertThat(stats.netStdDev()).as("one month has no spread").isNull();
        assertThat(stats.volatility())
                .as("a single month: a fifth of the net, there is nothing better to go on")
                .isEqualByComparingTo("1000000.00");
    }

    @Test
    void withoutAnyHistory_thereAreNoNumbers() {
        CashflowStats stats = CashflowStats.of(List.of(), NOW);

        assertThat(stats).isEqualTo(CashflowStats.none());
        assertThat(stats.hasData()).isFalse();
        assertThat(stats.months()).isZero();
        assertThat(stats.averageNet()).isEqualByComparingTo("0");
        assertThat(stats.volatility()).isEqualByComparingTo("0");
    }

    @Test
    void onlyTheLastSixCompleteMonthsCount() {
        List<MonthFlow> flows = new ArrayList<>();
        for (int month = 1; month <= 10; month++) { // January to October: nets 1M, 2M, ... 10M
            flows.add(flow(2026, month, month * 1_000_000 + "", "0"));
        }
        flows.add(flow(2026, 11, "0", "123456789"));

        CashflowStats stats = CashflowStats.of(flows, NOW);

        assertThat(stats.months()).isEqualTo(CashflowStats.MAX_MONTHS);
        assertThat(stats.averageNet()).as("May to October: 5..10 million").isEqualByComparingTo("7500000.00");
    }

    @Test
    void theOrderOfTheInputDoesNotMatter() {
        List<MonthFlow> flows = new ArrayList<>(List.of(
                flow(2026, 5, "9000000", "1000000"),
                flow(2026, 6, "7000000", "2000000"),
                flow(2026, 7, "8000000", "3000000"),
                flow(2026, 8, "6000000", "6500000"),
                flow(2026, 9, "10000000", "100000"),
                flow(2026, 10, "1000000", "5000000"),
                flow(2026, 4, "4000000", "100")));
        CashflowStats sorted = CashflowStats.of(flows, NOW);

        Collections.shuffle(flows, new Random(7));

        assertThat(CashflowStats.of(flows, NOW)).isEqualTo(sorted);
        assertThat(sorted.months()).isEqualTo(6); // April is the seventh and oldest: left out
    }

    @Test
    void identicalMonths_haveNoSpread_butTheVolatilityNeverDropsBelowOne() {
        List<MonthFlow> flows = List.of(
                flow(2026, 8, "5000000", "0"), flow(2026, 9, "5000000", "0"), flow(2026, 10, "5000000", "0"));

        CashflowStats stats = CashflowStats.of(flows, NOW);

        assertThat(stats.netStdDev()).isEqualByComparingTo("0.00");
        assertThat(stats.volatility()).isEqualByComparingTo("1.00");
    }

    @Test
    void aNegativeAverage_isKept() {
        List<MonthFlow> flows = List.of(flow(2026, 9, "10000000", "13000000"), flow(2026, 10, "10000000", "12000000"));

        CashflowStats stats = CashflowStats.of(flows, NOW);

        assertThat(stats.averageNet()).isEqualByComparingTo("-2500000.00");
        assertThat(stats.netStdDev()).isEqualByComparingTo("707106.78"); // sqrt(0.5) * 1,000,000
    }
}
