package io.github.thphuc06.jars.goals.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.goals.internal.domain.FeasibilityCalculator.Result;
import io.github.thphuc06.jars.goals.internal.domain.FeasibilityCalculator.Verdict;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeasibilityCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @ParameterizedTest(name = "{0} -> {1} months")
    @CsvSource({
        "2026-11-13, 1", // tomorrow: a started month counts as a month
        "2026-12-11, 1",
        "2026-12-12, 1", // exactly one month
        "2026-12-13, 2", // one month and a day
        "2027-02-12, 3",
        "2027-11-11, 12", // eleven months and thirty days
        "2027-11-12, 12",
        "2027-11-13, 13",
        "2029-11-12, 36",
        "2026-11-12, 0", // today
        "2026-11-11, 0", // the date has passed
        "2020-01-01, 0"
    })
    void monthsUntil_roundsAStartedMonthUp_andIsNeverNegative(String target, int expected) {
        assertThat(FeasibilityCalculator.monthsUntil(TODAY, LocalDate.parse(target)))
                .isEqualTo(expected);
    }

    @Test
    void aPaceBelowWhatIsNeeded_isOffTrack_andSaysByHowMuch() {
        // 20M saved of 120M, 10 months left, saving 8M a month: 20 + 80 = 100M at the date
        Result r = FeasibilityCalculator.evaluate(
                d("20000000.00"), d("120000000.00"), TODAY, LocalDate.of(2027, 9, 12), d("8000000.00"));

        assertThat(r.verdict()).isEqualTo(Verdict.OFF_TRACK);
        assertThat(r.monthsLeft()).isEqualTo(10);
        assertThat(r.remaining()).isEqualByComparingTo("100000000");
        assertThat(r.requiredMonthly()).isEqualByComparingTo("10000000");
        assertThat(r.projectedAtDeadline()).isEqualByComparingTo("100000000");
        assertThat(r.gap()).isEqualByComparingTo("20000000");
        assertThat(r.monthlyShortfall()).isEqualByComparingTo("2000000");
    }

    @Test
    void aPaceThatJustReachesTheTarget_isOnTrack() {
        Result r = FeasibilityCalculator.evaluate(
                d("20000000.00"), d("120000000.00"), TODAY, LocalDate.of(2027, 9, 12), d("10000000.00"));

        assertThat(r.verdict()).isEqualTo(Verdict.ON_TRACK);
        assertThat(r.projectedAtDeadline()).isEqualByComparingTo("120000000");
        assertThat(r.gap()).isEqualByComparingTo("0");
        assertThat(r.monthlyShortfall()).isEqualByComparingTo("0");
    }

    @Test
    void aFasterPace_isOnTrack_withNoShortfall() {
        Result r = FeasibilityCalculator.evaluate(
                d("0.00"), d("12000000.00"), TODAY, LocalDate.of(2027, 11, 12), d("2000000.00"));

        assertThat(r.verdict()).isEqualTo(Verdict.ON_TRACK);
        assertThat(r.requiredMonthly()).isEqualByComparingTo("1000000");
        assertThat(r.projectedAtDeadline()).isEqualByComparingTo("24000000");
        assertThat(r.monthlyShortfall()).isEqualByComparingTo("0");
    }

    @Test
    void whenTheJarAlreadyHoldsTheTarget_theGoalIsAchieved() {
        Result exact = FeasibilityCalculator.evaluate(
                d("120000000.00"), d("120000000.00"), TODAY, LocalDate.of(2027, 9, 12), null);
        Result over = FeasibilityCalculator.evaluate(
                d("130000000.00"), d("120000000.00"), TODAY, LocalDate.of(2027, 9, 12), d("-5000000.00"));

        for (Result r : new Result[] {exact, over}) {
            assertThat(r.verdict()).isEqualTo(Verdict.ACHIEVED);
            assertThat(r.remaining()).isEqualByComparingTo("0");
            assertThat(r.requiredMonthly()).isEqualByComparingTo("0");
            assertThat(r.gap()).isEqualByComparingTo("0");
            assertThat(r.monthlyShortfall()).isEqualByComparingTo("0");
        }
    }

    @Test
    void withoutAPace_thereIsNothingToSayAboutTheFuture_butTheRequirementIsKnown() {
        Result r = FeasibilityCalculator.evaluate(
                d("10000000.00"), d("70000000.00"), TODAY, LocalDate.of(2027, 5, 12), null);

        assertThat(r.verdict()).isEqualTo(Verdict.NO_DATA);
        assertThat(r.monthsLeft()).isEqualTo(6);
        assertThat(r.requiredMonthly()).isEqualByComparingTo("10000000");
        assertThat(r.projectedAtDeadline()).isNull();
        assertThat(r.gap()).isNull();
        assertThat(r.monthlyShortfall()).isNull();
    }

    @Test
    void aNegativePace_drainsTheSavings_butNeverBelowZero_andTheShortfallCountsItInFull() {
        Result r = FeasibilityCalculator.evaluate(
                d("5000000.00"), d("20000000.00"), TODAY, LocalDate.of(2027, 3, 12), d("-3000000.00"));

        assertThat(r.verdict()).isEqualTo(Verdict.OFF_TRACK);
        assertThat(r.monthsLeft()).isEqualTo(4);
        assertThat(r.projectedAtDeadline()).as("5M - 12M, floored at 0").isEqualByComparingTo("0");
        assertThat(r.gap()).isEqualByComparingTo("20000000");
        // 15M still missing over 4 months = 3,750,000 a month needed, plus the 3M that is lost every month
        assertThat(r.requiredMonthly()).isEqualByComparingTo("3750000");
        assertThat(r.monthlyShortfall()).isEqualByComparingTo("6750000");
    }

    @Test
    void whenTheDateHasCome_theWholeRemainderIsNeededNow() {
        Result r = FeasibilityCalculator.evaluate(
                d("10000000.00"), d("25000000.00"), TODAY, TODAY, d("1000000.00"));

        assertThat(r.monthsLeft()).isZero();
        assertThat(r.requiredMonthly()).isEqualByComparingTo("15000000");
        assertThat(r.projectedAtDeadline()).as("no month left to save in").isEqualByComparingTo("10000000");
        assertThat(r.verdict()).isEqualTo(Verdict.OFF_TRACK);
        assertThat(r.gap()).isEqualByComparingTo("15000000");
    }

    @Test
    void theRequiredAmountIsRoundedUpToTheCent_soTheTargetIsActuallyReached() {
        Result r = FeasibilityCalculator.evaluate(
                d("0.00"), d("100000.01"), TODAY, LocalDate.of(2027, 2, 12), d("0.00"));

        assertThat(r.requiredMonthly()).isEqualByComparingTo("33333.34"); // 3 x 33,333.34 >= 100,000.01
    }

    @ParameterizedTest(name = "p = {0}: grade {1}, hint {2} (gap {3})")
    @CsvSource({
        "1.0000, A, high, 0",
        "0.7500, A, high, 5",
        "0.7499, B, medium, 5",
        "0.4500, B, medium, 5",
        "0.4499, C, stretch, 5",
        "0.0000, C, stretch, 100",
        "0.2000, C, low, 0"
    })
    void gradeAndHint_followTheLegacyThresholds(String probability, String grade, String hint, String gap) {
        assertThat(FeasibilityCalculator.grade(d(probability))).isEqualTo(grade);
        assertThat(FeasibilityCalculator.hint(d(probability), d(gap))).isEqualTo(hint);
    }

    @Test
    void hint_withoutAGap_isLowWhenTheProbabilityIsLow() {
        assertThat(FeasibilityCalculator.hint(d("0.10"), null)).isEqualTo("low");
    }
}
