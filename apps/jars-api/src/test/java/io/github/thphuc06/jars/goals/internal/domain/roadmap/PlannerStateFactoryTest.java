package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.goals.internal.domain.CashflowStats;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PlannerState;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PlannerStateFactoryTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static CashflowStats stats(String income, String expense) {
        BigDecimal net = d(income).subtract(d(expense));
        return new CashflowStats(3, false, d(income), d(expense), net, null);
    }

    @ParameterizedTest(name = "capacity {0} -> {1}")
    @CsvSource({
        "-5000000, none",
        "-0.01, none",
        "0, none",
        "0.01, low",
        "1999999.99, low",
        "2000000, moderate", // legacy thresholds: 2,000,000 and 8,000,000 VND
        "7999999.99, moderate",
        "8000000, strong",
        "50000000, strong"
    })
    void capacityLabel_followsTheLegacyThresholds(String monthly, String label) {
        assertThat(PlannerStateFactory.capacityLabel(d(monthly))).isEqualTo(label);
    }

    @Test
    void capacityLabel_isUnknownWithoutAPace() {
        assertThat(PlannerStateFactory.capacityLabel(null)).isEqualTo("unknown");
        assertThat(PlannerStateFactory.cashflowStatus(null)).isEqualTo("unknown");
    }

    @ParameterizedTest(name = "net {0} -> {1}")
    @CsvSource({"1, positive", "-1, negative", "0, flat", "0.00, flat"})
    void cashflowStatus(String net, String status) {
        assertThat(PlannerStateFactory.cashflowStatus(d(net))).isEqualTo(status);
    }

    @ParameterizedTest(name = "runway {0} months -> {1}")
    @CsvSource({
        "0, critical",
        "0.99, critical",
        "1.00, high", // legacy _liquidity_pressure: under 1 critical, under 3 high, under 6 medium, else low
        "2.99, high",
        "3.00, medium",
        "5.99, medium",
        "6.00, low",
        "68.78, low"
    })
    void liquidityPressure_followsTheLegacyBands(String runway, String pressure) {
        assertThat(PlannerStateFactory.liquidityPressure(d(runway))).isEqualTo(pressure);
    }

    @Test
    void liquidityPressure_isUnknownWithoutARunway() {
        assertThat(PlannerStateFactory.liquidityPressure(null)).isEqualTo("unknown");
    }

    @Test
    void runway_isTheTotalInTheJarsOverTheAverageSpending() {
        assertThat(PlannerStateFactory.runwayMonths(stats("10000000", "5000000"), d("343900000")))
                .isEqualByComparingTo("68.78");
        assertThat(PlannerStateFactory.runwayMonths(stats("10000000", "5000000"), d("-100")))
                .as("overdrawn jars last no months")
                .isEqualByComparingTo("0");
        assertThat(PlannerStateFactory.runwayMonths(stats("10000000", "0"), d("1000"))).isNull();
        assertThat(PlannerStateFactory.runwayMonths(CashflowStats.none(), d("1000"))).isNull();
    }

    @ParameterizedTest(name = "capacity {0}, runway {1}, anomaly {2} -> {3}")
    @CsvSource({
        "5000000, 6, false, ready",
        "5000000, 2, false, cautious",
        "0, 6, false, cautious",
        "-1, 6, false, cautious",
        "5000000, 6, true, cautious",
        "-1, 2, false, not_ready",
        "5000000, 2, true, not_ready",
        "-1, 2, true, not_ready"
    })
    void readinessLabel_countsTheProblems(String capacity, String runway, boolean anomaly, String label) {
        assertThat(PlannerStateFactory.readinessLabel(d(capacity), d(runway), anomaly))
                .isEqualTo(label);
    }

    @Test
    void readinessLabel_andRiskBand_areUnknownWhenThePaceOrTheRunwayIsUnknown() {
        assertThat(PlannerStateFactory.readinessLabel(null, d("6"), false)).isEqualTo("unknown");
        assertThat(PlannerStateFactory.readinessLabel(d("1"), null, false)).isEqualTo("unknown");
        assertThat(PlannerStateFactory.riskBand(null, d("6"), false)).isEqualTo("unknown");
        assertThat(PlannerStateFactory.riskBand(d("1"), null, false)).isEqualTo("unknown");
    }

    @ParameterizedTest(name = "capacity {0}, runway {1}, anomaly {2} -> {3}")
    @CsvSource({
        "5000000, 0.5, false, critical",
        "-1, 2, false, critical",
        "-1, 4, false, high",
        "5000000, 2, false, high",
        "0, 8, false, low", // a zero pace is not negative: it is a readiness problem, not a risk by itself
        "5000000, 4, false, moderate",
        "5000000, 8, true, moderate",
        "5000000, 8, false, low"
    })
    void riskBand(String capacity, String runway, boolean anomaly, String band) {
        assertThat(PlannerStateFactory.riskBand(d(capacity), d(runway), anomaly)).isEqualTo(band);
    }

    @ParameterizedTest
    @CsvSource({"94380000, 1500000000, 0.06292", "0, 1000, 0.00000", "1000, 1000, 1.00000", "5000, 1000, 1.00000", "-5, 1000, 0.00000"})
    void progressRatio_isClampedBetweenZeroAndOne(String funding, String target, String expected) {
        assertThat(PlannerStateFactory.progressRatio(d(funding), d(target))).isEqualByComparingTo(expected);
    }

    @Test
    void progressRatio_withoutATarget_isZero() {
        assertThat(PlannerStateFactory.progressRatio(d("100"), BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void build_labelsAStateLikeTheLegacyMockRoadmap() {
        // the mock: -1,609,776.54 a month, 68.78 months of runway (343.9M in the jars, 5M spent a month), 94.38M saved
        // of 1.5B, an open anomaly
        CashflowStats stats = new CashflowStats(
                3, false, d("3390223.46"), d("5000000.00"), d("-1609776.54"), d("250000.00"));

        PlannerState state = PlannerStateFactory.build(
                stats, d("343900000"), d("94380000"), d("1500000000"), 1, "low");

        assertThat(state.cashflowStatus()).isEqualTo("negative");
        assertThat(state.savingsCapacity().amountMonthly()).isEqualByComparingTo("-1609776.54");
        assertThat(state.savingsCapacity().label()).isEqualTo("none");
        assertThat(state.runwayMonths()).isEqualByComparingTo("68.78");
        assertThat(state.liquidityPressure()).isEqualTo("low");
        assertThat(state.anomalyState()).isEqualTo("active");
        assertThat(state.feasibilityHint()).isEqualTo("low");
        assertThat(state.goalProgressRatio()).isEqualByComparingTo("0.06292");
        assertThat(state.bufferStatus()).isEqualTo("present");
        assertThat(state.source()).isEqualTo("jars_ledger");
        // where this system defines the label differently from the mock's planner agent:
        assertThat(state.readinessLabel()).as("two problems: no surplus and an open anomaly").isEqualTo("not_ready");
        assertThat(state.riskBand()).as("a negative pace with a long runway").isEqualTo("high");
    }

    @Test
    void build_withoutHistory_leavesEverythingThatCannotBeKnownUnknown() {
        PlannerState state = PlannerStateFactory.build(
                CashflowStats.none(), d("0"), d("0"), d("1000000"), 0, null);

        assertThat(state.cashflowStatus()).isEqualTo("unknown");
        assertThat(state.savingsCapacity().amountMonthly()).isNull();
        assertThat(state.savingsCapacity().label()).isEqualTo("unknown");
        assertThat(state.runwayMonths()).isNull();
        assertThat(state.liquidityPressure()).isEqualTo("unknown");
        assertThat(state.readinessLabel()).isEqualTo("unknown");
        assertThat(state.riskBand()).isEqualTo("unknown");
        assertThat(state.feasibilityHint()).isEqualTo("unknown");
        assertThat(state.anomalyState()).isEqualTo("none");
        assertThat(state.bufferStatus()).isEqualTo("missing");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"unknown"})
    void build_keepsAGivenHintAndTurnsNullIntoUnknown(String hint) {
        PlannerState state = PlannerStateFactory.build(stats("10", "5"), d("100"), d("0"), d("100"), 0, hint);

        assertThat(state.feasibilityHint()).isEqualTo("unknown");
    }
}
