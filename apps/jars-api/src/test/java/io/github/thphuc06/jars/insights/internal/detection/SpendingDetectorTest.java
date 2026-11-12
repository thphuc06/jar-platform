package io.github.thphuc06.jars.insights.internal.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.thphuc06.jars.insights.internal.detection.SpendingDetector.Detector;
import io.github.thphuc06.jars.insights.internal.detection.SpendingDetector.Direction;
import io.github.thphuc06.jars.insights.internal.detection.SpendingDetector.Finding;
import io.github.thphuc06.jars.insights.internal.detection.SpendingDetector.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpendingDetectorTest {

    private final SpendingDetector detector = new SpendingDetector();

    // ----------------------------------------------- golden vectors from the hackathon's Python tests

    /**
     * The original tests used a frozen baseline (mean 100,000, sigma 52,000). A state that has already learned a million
     * samples barely moves when it learns one more, so it reproduces that frozen baseline.
     */
    private static DetectorState frozenBaseline(double mean, double sigma) {
        int count = 1_000_000;
        return new DetectorState(count, mean, sigma * sigma * (count - 1), 0, 0, List.of());
    }

    @Test
    void goldenNormalSpending_neverAlarms() {
        // test_1_normal_spending
        DetectorState state = frozenBaseline(100_000, 52_000);

        for (double amount : new double[] {95_000, 102_000, 98_000, 101_000, 99_000}) {
            assertThat(detector.observe(state, amount)).isEmpty();
        }
    }

    @Test
    void goldenGradualDrift_firesOnTheTenthIncreasingExpense() {
        // test_3_gradual_drift: 110k, 120k ... 200k. S+ = 0, 0, 4k, 18k, 42k, 76k, 120k, 174k, 238k, 312k (in units of
        // 1k
        // after subtracting k = 26k), so it crosses h = 260k exactly at the last value
        DetectorState state = frozenBaseline(100_000, 52_000);
        List<Optional<Finding>> results = new ArrayList<>();

        for (int amount = 110_000; amount <= 200_000; amount += 10_000) {
            results.add(detector.observe(state, amount));
        }

        assertThat(results.subList(0, 9)).allSatisfy(r -> assertThat(r).isEmpty());
        Finding finding = results.get(9).orElseThrow();
        assertThat(finding.detector()).isEqualTo(Detector.CUSUM);
        assertThat(finding.direction()).isEqualTo(Direction.UP);
        assertThat(finding.score()).isCloseTo(312_000 / 52_000.0, within(0.01)); // 6.0 sigmas
        assertThat(finding.threshold()).isEqualTo(5.0);
    }

    @Test
    void goldenResetAfterDrift_accumulatorsGoBackToZero() {
        // test_6_reset_after_drift: a constant 200k against mean 100k, sigma 52k fires on the 4th expense
        DetectorState state = frozenBaseline(100_000, 52_000);
        int firedAt = -1;

        for (int i = 1; i <= 15 && firedAt < 0; i++) {
            if (detector.observe(state, 200_000).isPresent()) {
                firedAt = i;
            }
        }

        assertThat(firedAt).isEqualTo(4);
        assertThat(state.cusumPos()).isZero();
        assertThat(state.cusumNeg()).isZero();
    }

    // ----------------------------------------------- warm-up and baseline

    @Test
    void firstExpensesOnlyTrainTheBaseline_evenIfTheyAreWild() {
        DetectorState state = new DetectorState();

        for (double amount : new double[] {10, 1_000_000, 5, 900_000, 20}) {
            assertThat(detector.observe(state, amount)).isEmpty();
        }

        assertThat(state.count()).isEqualTo(5);
    }

    @Test
    void welfordBaseline_matchesTheTextbookMeanAndSampleDeviation() {
        DetectorState state = new DetectorState();
        for (double amount : new double[] {2, 4, 4, 4, 5, 5, 7, 9}) {
            state.learn(amount);
        }

        assertThat(state.mean()).isCloseTo(5.0, within(1e-12));
        assertThat(state.sigma()).isCloseTo(Math.sqrt(32.0 / 7.0), within(1e-12)); // sample variance, n - 1
    }

    @Test
    void recentWindow_keepsOnlyTheLastConfiguredAmounts() {
        DetectorState state = new DetectorState();

        for (int i = 1; i <= 50; i++) {
            detector.observe(state, i * 1_000.0);
        }

        assertThat(state.recent()).hasSize(SpendingDetector.DEFAULT.windowSize());
        assertThat(state.recent().getLast()).isEqualTo(50_000.0);
        assertThat(state.recent().getFirst()).isEqualTo(21_000.0);
    }

    // ----------------------------------------------- MAD spike

    private DetectorState trainedOnSteadySpending() {
        DetectorState state = new DetectorState();
        double[] pattern = {95_000, 105_000, 98_000, 102_000, 100_000, 97_000, 103_000, 99_000, 101_000, 100_000};
        for (int round = 0; round < 3; round++) {
            for (double amount : pattern) {
                assertThat(detector.observe(state, amount))
                        .as("training data is normal")
                        .isEmpty();
            }
        }
        return state;
    }

    @Test
    void aSingleHugeExpense_isAMadSpikeWithHighSeverity() {
        DetectorState state = trainedOnSteadySpending();

        Finding finding = detector.observe(state, 1_000_000).orElseThrow();

        assertThat(finding.detector()).isEqualTo(Detector.MAD);
        assertThat(finding.direction()).isEqualTo(Direction.UP);
        assertThat(finding.severity()).isEqualTo(Severity.HIGH);
        assertThat(finding.score()).isGreaterThan(7.0);
        assertThat(finding.threshold()).isEqualTo(3.5);
    }

    @Test
    void aSpikeDoesNotPoisonTheBaselineOrCusum() {
        DetectorState state = trainedOnSteadySpending();
        int countBefore = state.count();
        double meanBefore = state.mean();

        detector.observe(state, 1_000_000);

        assertThat(state.count()).as("spike is not learned").isEqualTo(countBefore);
        assertThat(state.mean()).isEqualTo(meanBefore);
        assertThat(state.cusumPos()).isZero();
        assertThat(detector.observe(state, 100_000))
                .as("normal spending right after is normal")
                .isEmpty();
    }

    @Test
    void aModestIncrease_isNotASpike() {
        DetectorState state = trainedOnSteadySpending();

        assertThat(detector.observe(state, 110_000)).isEmpty();
    }

    @Test
    void identicalHistory_stillFlagsARealJump_butNotATinyOne() {
        DetectorState state = new DetectorState();
        for (int i = 0; i < 40; i++) {
            detector.observe(state, 50_000);
        }

        assertThat(detector.observe(state, 50_000)).isEmpty();
        assertThat(detector.observe(state, 51_000))
                .as("MAD is floored, so +2% is not infinite")
                .isEmpty();
        Finding finding = detector.observe(state, 150_000).orElseThrow();
        assertThat(finding.detector()).isEqualTo(Detector.MAD);
    }

    // ----------------------------------------------- CUSUM drift on a realistic series

    @Test
    void sustainedHigherSpending_triggersAnUpwardDriftEvenWhenNoSingleExpenseIsExtreme() {
        DetectorState state = trainedOnSteadySpending();
        Finding found = null;

        // 125k each time: never a MAD spike (modified z about 3.4), but consistently +2.5 sigma above the baseline
        for (int i = 0; i < 6 && found == null; i++) {
            found = detector.observe(state, 125_000).orElse(null);
        }

        assertThat(found).isNotNull();
        assertThat(found.detector()).isEqualTo(Detector.CUSUM);
        assertThat(found.direction()).isEqualTo(Direction.UP);
    }

    @Test
    void sustainedLowerSpending_triggersALowSeverityDownwardDrift() {
        DetectorState state = trainedOnSteadySpending();
        int firedAt = -1;
        Finding finding = null;

        for (int i = 1; i <= 8 && finding == null; i++) {
            finding = detector.observe(state, 70_000).orElse(null);
            firedAt = i;
        }

        assertThat(finding).isNotNull();
        assertThat(firedAt).isBetween(2, 4);
        assertThat(finding.direction()).isEqualTo(Direction.DOWN);
        assertThat(finding.severity()).isEqualTo(Severity.LOW);
        assertThat(finding.score()).isGreaterThan(5.0);
    }

    // ----------------------------------------------- helpers

    @Test
    void median_ofOddAndEvenLists() {
        assertThat(SpendingDetector.median(List.of(5.0, 1.0, 3.0))).isEqualTo(3.0);
        assertThat(SpendingDetector.median(List.of(4.0, 1.0, 3.0, 2.0))).isEqualTo(2.5);
        assertThat(SpendingDetector.median(List.of(7.0))).isEqualTo(7.0);
    }

    @Test
    void customConfig_changesSensitivity() {
        SpendingDetector sensitive =
                new SpendingDetector(new SpendingDetector.Config(5, 30, 8, 3.5, 0.05, 0.10, 0.25, 2.0));
        DetectorState state = trainedOnSteadySpending();

        Finding found = null;
        for (int i = 0; i < 5 && found == null; i++) {
            found = sensitive.observe(state, 115_000).orElse(null);
        }

        assertThat(found)
                .as("h = 2 sigmas fires on a mild but persistent increase")
                .isNotNull();
    }
}
