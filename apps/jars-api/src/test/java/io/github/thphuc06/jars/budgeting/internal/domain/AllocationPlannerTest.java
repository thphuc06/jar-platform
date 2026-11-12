package io.github.thphuc06.jars.budgeting.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.budgeting.internal.domain.AllocationPlanner.JarProfile;
import io.github.thphuc06.jars.budgeting.internal.domain.AllocationPlanner.Plan;
import io.github.thphuc06.jars.budgeting.internal.domain.AllocationPlanner.Share;
import io.github.thphuc06.jars.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AllocationPlannerTest {

    private static final UUID NEC = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID FFA = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID LTSS = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final UUID EDU = UUID.fromString("00000000-0000-7000-8000-000000000004");
    private static final UUID PLAY = UUID.fromString("00000000-0000-7000-8000-000000000005");
    private static final UUID GIVE = UUID.fromString("00000000-0000-7000-8000-000000000006");

    private static JarProfile jar(UUID id, String targetPct, String averageSpend) {
        return new JarProfile(id, new BigDecimal(targetPct), new BigDecimal(averageSpend));
    }

    /** The six default jars of the 6-jars method (55/10/10/10/10/5) with no spending history. */
    private static List<JarProfile> sixJars() {
        return List.of(
                jar(NEC, "55", "0"),
                jar(FFA, "10", "0"),
                jar(LTSS, "10", "0"),
                jar(EDU, "10", "0"),
                jar(PLAY, "10", "0"),
                jar(GIVE, "5", "0"));
    }

    private static List<String> amounts(Plan plan) {
        return plan.shares().stream()
                .map(s -> s.amount().amount().toPlainString())
                .toList();
    }

    private static List<String> percentages(Plan plan) {
        return plan.shares().stream().map(s -> s.sharePct().toPlainString()).toList();
    }

    private static BigDecimal sumOfAmounts(Plan plan) {
        return plan.shares().stream().map(s -> s.amount().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void withoutHistory_followsThePlannedPercentages() {
        Plan plan = AllocationPlanner.plan(Money.vnd("10000000"), sixJars(), 0);

        assertThat(amounts(plan))
                .containsExactly("5500000.00", "1000000.00", "1000000.00", "1000000.00", "1000000.00", "500000.00");
        assertThat(percentages(plan)).containsExactly("55.00", "10.00", "10.00", "10.00", "10.00", "5.00");
        assertThat(plan.historyWeight()).isEqualByComparingTo("0");
        assertThat(plan.monthsOfHistory()).isZero();
    }

    static Stream<String> tricky() {
        return Stream.of("0.01", "1", "99.99", "100", "1000000.07", "12345678.91", "9999999999.99", "33.33");
    }

    @ParameterizedTest
    @MethodSource("tricky")
    void amountsAddUpToTheIncomeExactly_andPercentagesToOneHundred(String income) {
        for (int months : new int[] {0, 1, 3, 6, 12}) {
            List<JarProfile> jars = List.of(
                    jar(NEC, "55", "3000000"),
                    jar(FFA, "10", "10"),
                    jar(LTSS, "10", "0"),
                    jar(EDU, "10", "123456.78"),
                    jar(PLAY, "10", "888888.88"),
                    jar(GIVE, "5", "0.01"));

            Plan plan = AllocationPlanner.plan(Money.vnd(income), jars, months);

            assertThat(sumOfAmounts(plan))
                    .as("income %s, %d months", income, months)
                    .isEqualByComparingTo(income);
            assertThat(plan.shares().stream().map(Share::sharePct).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo("100.00");
            plan.shares().forEach(s -> assertThat(s.amount().amount().signum()).isGreaterThanOrEqualTo(0));
        }
    }

    @Test
    void sharesAreNormalisedWhenThePlannedPercentagesDoNotAddUpToOneHundred() {
        // 30% and 20% of nothing in particular: only the proportion 60:40 counts
        Plan plan = AllocationPlanner.plan(Money.vnd("1000"), List.of(jar(NEC, "30", "0"), jar(FFA, "20", "0")), 0);

        assertThat(amounts(plan)).containsExactly("600.00", "400.00");
        assertThat(percentages(plan)).containsExactly("60.00", "40.00");
    }

    @Test
    void whenNothingIsPlanned_theIncomeIsSplitEqually() {
        Plan plan = AllocationPlanner.plan(
                Money.vnd("100"), List.of(jar(NEC, "0", "0"), jar(FFA, "0", "0"), jar(LTSS, "0", "0")), 0);

        assertThat(amounts(plan)).containsExactly("33.34", "33.33", "33.33");
        assertThat(percentages(plan)).containsExactly("33.34", "33.33", "33.33");
    }

    @Test
    void history_nudgesTowardWhatEachJarReallySpends_withWeightGrowingWithTheMonths() {
        // planned 50/50, but jar B spends three times as much as jar A: spent shares are 25% / 75%
        List<JarProfile> jars = List.of(jar(NEC, "50", "100"), jar(FFA, "50", "300"));

        Plan sixMonths = AllocationPlanner.plan(Money.vnd("1000"), jars, 6); // weight 0.20
        Plan threeMonths = AllocationPlanner.plan(Money.vnd("1000"), jars, 3); // weight 0.10
        Plan oneMonth = AllocationPlanner.plan(Money.vnd("1000"), jars, 1); // weight 0.20 / 6

        // 0.8 * 0.5 + 0.2 * 0.25 = 0.45 and 0.8 * 0.5 + 0.2 * 0.75 = 0.55
        assertThat(amounts(sixMonths)).containsExactly("450.00", "550.00");
        assertThat(sixMonths.historyWeight()).isEqualByComparingTo("0.2");
        assertThat(amounts(threeMonths)).containsExactly("475.00", "525.00");
        assertThat(threeMonths.historyWeight()).isEqualByComparingTo("0.1");
        assertThat(oneMonth.shares().get(1).amount().amount())
                .isBetween(new BigDecimal("500.00"), new BigDecimal("525.00"));
        assertThat(oneMonth.historyWeight()).isEqualByComparingTo("0.0333");
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 7, 12, 120})
    void historyBeyondSixMonths_doesNotCountMore(int months) {
        List<JarProfile> jars = List.of(jar(NEC, "50", "100"), jar(FFA, "50", "300"));

        Plan plan = AllocationPlanner.plan(Money.vnd("1000"), jars, months);

        assertThat(amounts(plan)).containsExactly("450.00", "550.00");
        assertThat(plan.historyWeight()).isEqualByComparingTo("0.2");
    }

    @Test
    void history_neverFundsAJarThatThePlanLeavesAtZero() {
        // PLAY is planned at 0% but spent a lot: it stays at 0, the nudge only redistributes among planned jars
        List<JarProfile> jars = List.of(jar(NEC, "70", "100"), jar(FFA, "30", "100"), jar(PLAY, "0", "5000"));

        Plan plan = AllocationPlanner.plan(Money.vnd("1000"), jars, 6);

        assertThat(plan.shares().get(2).amount().amount()).isEqualByComparingTo("0");
        assertThat(plan.shares().get(2).sharePct()).isEqualByComparingTo("0");
        // spent shares among the planned jars are 50/50: 0.8 * 0.7 + 0.2 * 0.5 = 0.66 and 0.8 * 0.3 + 0.2 * 0.5 = 0.34
        assertThat(amounts(plan)).containsExactly("660.00", "340.00", "0.00");
    }

    @Test
    void withoutAnySpending_historyHasNothingToSay() {
        Plan plan = AllocationPlanner.plan(Money.vnd("10000000"), sixJars(), 6);

        assertThat(amounts(plan))
                .containsExactly("5500000.00", "1000000.00", "1000000.00", "1000000.00", "1000000.00", "500000.00");
        assertThat(plan.historyWeight()).isEqualByComparingTo("0");
        assertThat(plan.monthsOfHistory()).isEqualTo(6);
    }

    @Test
    void theSharesKeepTheJarsAndTheirPlannedPercentages() {
        Plan plan = AllocationPlanner.plan(Money.vnd("1000"), sixJars(), 0);

        assertThat(plan.shares()).extracting(Share::jarId).containsExactly(NEC, FFA, LTSS, EDU, PLAY, GIVE);
        assertThat(plan.shares().getFirst().targetPct()).isEqualByComparingTo("55");
        assertThat(plan.shares().getFirst().amount().currency()).isEqualTo(Money.VND);
    }

    @Test
    void theSameInputGivesTheSamePlan() {
        List<JarProfile> jars = List.of(jar(NEC, "55", "1234567.89"), jar(FFA, "45", "7654321.12"));

        assertThat(AllocationPlanner.plan(Money.vnd("5000000.01"), jars, 4))
                .isEqualTo(AllocationPlanner.plan(Money.vnd("5000000.01"), jars, 4));
    }

    @Test
    void aPlanNeedsAtLeastOneJar() {
        assertThatThrownBy(() -> AllocationPlanner.plan(Money.vnd("1000"), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
