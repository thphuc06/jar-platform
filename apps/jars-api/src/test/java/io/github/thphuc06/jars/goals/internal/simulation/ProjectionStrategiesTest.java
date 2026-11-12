package io.github.thphuc06.jars.goals.internal.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.goals.internal.simulation.ProjectionStrategy.PathPoint;
import io.github.thphuc06.jars.goals.internal.simulation.ProjectionStrategy.ProjectionInput;
import io.github.thphuc06.jars.goals.internal.simulation.ProjectionStrategy.ProjectionOutcome;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProjectionStrategiesTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static ProjectionInput input(String start, String target, int months, String mean, String sd, long seed) {
        return new ProjectionInput(d(start), d(target), months, d(mean), d(sd), seed);
    }

    // ------------------------------------------------------------------ the input

    @Test
    void anInputNeedsAtLeastOneMonth_andANonNegativeSpread() {
        assertThatThrownBy(() -> input("0", "1", 0, "1", "1", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> input("0", "1", 12, "1", "-1", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(input("0", "1", 1, "1", "0", 1).months()).isEqualTo(1);
    }

    @Test
    void withMean_changesOnlyTheMean() {
        ProjectionInput base = input("10", "20", 6, "1", "2", 99);

        ProjectionInput changed = base.withMean(d("5"));

        assertThat(changed.monthlyMean()).isEqualByComparingTo("5");
        assertThat(changed.startingAmount()).isEqualTo(base.startingAmount());
        assertThat(changed.targetAmount()).isEqualTo(base.targetAmount());
        assertThat(changed.months()).isEqualTo(6);
        assertThat(changed.monthlyStdDev()).isEqualTo(base.monthlyStdDev());
        assertThat(changed.seed()).isEqualTo(99);
    }

    // ------------------------------------------------------------------ the straight line

    @Test
    void deterministic_addsTheMeanEveryMonth() {
        ProjectionOutcome o =
                new DeterministicProjection().project(input("1000000", "5000000", 4, "500000", "100000", 1));

        assertThat(o.strategy()).isEqualTo("DETERMINISTIC");
        assertThat(o.path()).extracting(PathPoint::month).containsExactly(0, 1, 2, 3, 4);
        assertThat(o.path())
                .extracting(p -> p.p50().toPlainString())
                .containsExactly("1000000.00", "1500000.00", "2000000.00", "2500000.00", "3000000.00");
        assertThat(o.p10()).isEqualTo(o.p50()).isEqualTo(o.p90());
        assertThat(o.p50()).isEqualByComparingTo("3000000");
        assertThat(o.reachesTarget()).isFalse();
        assertThat(o.probabilityOfSuccess()).isEqualByComparingTo("0");
    }

    @Test
    void deterministic_reachingTheTargetIsCertain() {
        ProjectionOutcome o = new DeterministicProjection().project(input("0", "3000000", 3, "1000000", "5", 1));

        assertThat(o.reachesTarget()).isTrue();
        assertThat(o.probabilityOfSuccess()).isEqualByComparingTo("1");
    }

    @Test
    void deterministic_neverGoesBelowZero() {
        ProjectionOutcome o = new DeterministicProjection().project(input("1000", "5000", 5, "-400", "0", 1));

        assertThat(o.path())
                .extracting(p -> p.p50().toPlainString())
                .containsExactly("1000.00", "600.00", "200.00", "0.00", "0.00", "0.00");
    }

    @Test
    void deterministic_theLegacyMockCapacity() {
        ProjectionOutcome o = new DeterministicProjection()
                .project(new ProjectionInput(
                        d("94380000"),
                        d("1500000000"),
                        36,
                        new BigDecimal("-1609776.536388888888888888889"),
                        BigDecimal.ONE,
                        1));

        assertThat(o.p50()).isEqualByComparingTo("36428044.69");
        assertThat(o.path().get(12).p50()).isEqualByComparingTo("75062681.56");
        assertThat(o.path().get(24).p50()).isEqualByComparingTo("55745363.13");
    }

    // ------------------------------------------------------------------ the stress case

    @Test
    void stress_takesOneStandardDeviationOffEveryMonth() {
        ProjectionOutcome o = new StressProjection().project(input("0", "5000000", 4, "1000000", "250000", 1));

        assertThat(o.strategy()).isEqualTo("STRESS");
        assertThat(o.p50()).as("4 x (1,000,000 - 250,000)").isEqualByComparingTo("3000000");
        assertThat(o.reachesTarget()).isFalse();
    }

    @Test
    void stress_isNeverBetterThanTheStraightLine() {
        ProjectionInput in = input("500000", "9000000", 12, "800000", "300000", 5);

        assertThat(new StressProjection().project(in).p50())
                .isLessThan(new DeterministicProjection().project(in).p50());
    }

    @Test
    void stress_canShowThatAPlanDoesNotDependOnGoodMonths() {
        ProjectionInput in = input("0", "6000000", 6, "2000000", "500000", 1);

        assertThat(new StressProjection().project(in).reachesTarget()).isTrue(); // 6 x 1,500,000 = 9,000,000
    }

    // ------------------------------------------------------------------ percentiles

    @Test
    void percentiles_interpolateLinearly_likeTheLegacyFunction() {
        double[] values = {4, 1, 3, 2};

        assertThat(Percentiles.of(values, 0.5)).isEqualTo(2.5);
        assertThat(Percentiles.of(values, 0.0)).isEqualTo(1.0);
        assertThat(Percentiles.of(values, 1.0)).isEqualTo(4.0);
        assertThat(Percentiles.of(values, 0.25)).isEqualTo(1.75);
        assertThat(Percentiles.of(values, 0.9)).isEqualTo(3.7, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(Percentiles.of(values, -1)).as("clamped").isEqualTo(1.0);
        assertThat(Percentiles.of(values, 7)).as("clamped").isEqualTo(4.0);
        assertThat(Percentiles.of(new double[] {42}, 0.9)).isEqualTo(42.0);
        assertThat(values).as("the input is not reordered").containsExactly(4, 1, 3, 2);
        assertThatThrownBy(() -> Percentiles.of(new double[0], 0.5)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ Monte Carlo

    @Test
    void monteCarlo_theSameInputGivesExactlyTheSameResult() {
        ProjectionInput in = input("2000000", "15000000", 12, "1000000", "400000", 20261112L);

        ProjectionOutcome first = new MonteCarloProjection().project(in);
        ProjectionOutcome second = new MonteCarloProjection().project(in);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void monteCarlo_aDifferentSeedGivesADifferentResult() {
        ProjectionInput in = input("2000000", "15000000", 12, "1000000", "400000", 1);

        ProjectionOutcome a = new MonteCarloProjection().project(in);
        ProjectionOutcome b = new MonteCarloProjection()
                .project(new ProjectionInput(
                        in.startingAmount(), in.targetAmount(), in.months(), in.monthlyMean(), in.monthlyStdDev(), 2));

        assertThat(b.p50()).isNotEqualByComparingTo(a.p50());
        assertThat(b.p10()).isNotEqualByComparingTo(a.p10());
        assertThat(b.path()).isNotEqualTo(a.path());
    }

    @Test
    void monteCarlo_theSpreadMatchesTheTheory() {
        // no floor in play: 1e9 start, sd 1,000,000 a month, 12 months: the end is N(1e9, 1e6 * sqrt(12))
        ProjectionOutcome o =
                new MonteCarloProjection().project(input("1000000000", "1000000000", 12, "0", "1000000", 42));

        double sd = 1_000_000 * Math.sqrt(12);
        assertThat(o.p50().doubleValue()).isCloseTo(1_000_000_000.0, org.assertj.core.data.Offset.offset(0.1 * sd));
        // the 10th and 90th percentiles of a normal are 1.2816 deviations away
        assertThat(o.p10().doubleValue())
                .isCloseTo(1_000_000_000.0 - 1.2816 * sd, org.assertj.core.data.Offset.offset(0.12 * sd));
        assertThat(o.p90().doubleValue())
                .isCloseTo(1_000_000_000.0 + 1.2816 * sd, org.assertj.core.data.Offset.offset(0.12 * sd));
        assertThat(o.probabilityOfSuccess().doubleValue())
                .as("half the futures end above the start")
                .isBetween(0.45, 0.55);
        assertThat(o.p10()).isLessThan(o.p50());
        assertThat(o.p50()).isLessThan(o.p90());
    }

    @Test
    void monteCarlo_withoutSpread_isTheStraightLine() {
        ProjectionInput in = input("1000000", "4000000", 6, "600000", "0", 7);

        ProjectionOutcome mc = new MonteCarloProjection().project(in);
        ProjectionOutcome line = new DeterministicProjection().project(in);

        assertThat(mc.p10()).isEqualByComparingTo(line.p50());
        assertThat(mc.p50()).isEqualByComparingTo(line.p50());
        assertThat(mc.p90()).isEqualByComparingTo(line.p50());
        assertThat(mc.probabilityOfSuccess()).isEqualByComparingTo("1"); // 1,000,000 + 6 x 600,000 = 4,600,000
        assertThat(mc.path()).hasSameSizeAs(line.path());
    }

    @Test
    void monteCarlo_aTargetFarBeyondReach_hasNoChance_andOneWithinSureReach_isCertain() {
        ProjectionOutcome impossible =
                new MonteCarloProjection().project(input("0", "1000000000", 12, "1000000", "100000", 3));
        ProjectionOutcome sure = new MonteCarloProjection().project(input("0", "1000000", 12, "5000000", "100000", 3));

        assertThat(impossible.probabilityOfSuccess()).isEqualByComparingTo("0");
        assertThat(impossible.reachesTarget()).isFalse();
        assertThat(sure.probabilityOfSuccess()).isEqualByComparingTo("1");
        assertThat(sure.reachesTarget()).isTrue();
    }

    @Test
    void monteCarlo_theProbabilityGrowsWithTheMean() {
        BigDecimal previous = new BigDecimal("-1");
        for (String mean : List.of("500000", "800000", "1000000", "1200000", "1500000")) {
            BigDecimal p = new MonteCarloProjection()
                    .project(input("0", "12000000", 12, mean, "600000", 11))
                    .probabilityOfSuccess();

            assertThat(p).isGreaterThanOrEqualTo(previous);
            previous = p;
        }
        assertThat(previous).isGreaterThan(new BigDecimal("0.9"));
    }

    @Test
    void monteCarlo_theAmountNeverDropsBelowZero() {
        ProjectionOutcome o =
                new MonteCarloProjection().project(input("100000", "1000000", 24, "-200000", "300000", 5));

        assertThat(o.path()).allSatisfy(p -> {
            assertThat(p.p10()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(p.p50()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        });
        assertThat(o.p50()).isEqualByComparingTo("0");
    }

    @Test
    void monteCarlo_thePathStartsAtTheStartingAmount_andHasOnePointPerMonth() {
        ProjectionOutcome o =
                new MonteCarloProjection().project(input("2500000", "9000000", 10, "300000", "100000", 8));

        assertThat(o.path()).hasSize(11);
        assertThat(o.path().getFirst().month()).isZero();
        assertThat(o.path().getFirst().p10()).isEqualByComparingTo("2500000");
        assertThat(o.path().getFirst().p50()).isEqualByComparingTo("2500000");
        assertThat(o.path().getFirst().p90()).isEqualByComparingTo("2500000");
        assertThat(o.path().getLast().month()).isEqualTo(10);
        assertThat(o.p50()).isEqualTo(o.path().getLast().p50());
    }

    @Test
    void monteCarlo_aScenarioThatOnlyShiftsTheMean_shiftsEveryOutcomeByTheSameAmount() {
        // common random numbers: the same seed means the same random draws, so +1,000,000 a month for 12 months is
        // exactly +12,000,000 on every percentile (nothing is near the floor here)
        ProjectionInput base = input("50000000", "90000000", 12, "2000000", "700000", 4242);

        ProjectionOutcome a = new MonteCarloProjection().project(base);
        ProjectionOutcome b = new MonteCarloProjection().project(base.withMean(d("3000000")));

        assertThat(b.p10().subtract(a.p10())).isBetween(d("11999999.98"), d("12000000.02"));
        assertThat(b.p50().subtract(a.p50())).isBetween(d("11999999.98"), d("12000000.02"));
        assertThat(b.p90().subtract(a.p90())).isBetween(d("11999999.98"), d("12000000.02"));
        assertThat(b.probabilityOfSuccess()).isGreaterThan(a.probabilityOfSuccess());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100, 500})
    void monteCarlo_theNumberOfDrawsIsConfigurable_andTheProbabilityIsAMultipleOfOneOverN(int draws) {
        ProjectionOutcome o = new MonteCarloProjection(draws).project(input("0", "6000000", 6, "1000000", "800000", 9));

        BigDecimal times = o.probabilityOfSuccess().multiply(BigDecimal.valueOf(draws));
        assertThat(times.setScale(0, java.math.RoundingMode.HALF_EVEN)
                        .subtract(times)
                        .abs())
                .isLessThan(new BigDecimal("0.01"));
    }

    @Test
    void monteCarlo_needsAtLeastOneDraw() {
        assertThatThrownBy(() -> new MonteCarloProjection(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new MonteCarloProjection().name()).isEqualTo("MONTE_CARLO");
        assertThat(MonteCarloProjection.DEFAULT_DRAWS).isEqualTo(2_000);
    }

    @Test
    void theStrategiesAreInterchangeable() {
        ProjectionInput in = input("0", "1000000", 5, "250000", "50000", 1);
        List<ProjectionStrategy> all =
                List.of(new DeterministicProjection(), new StressProjection(), new MonteCarloProjection());

        assertThat(all.stream().map(s -> s.project(in).strategy()).toList())
                .containsExactly("DETERMINISTIC", "STRESS", "MONTE_CARLO");
        assertThat(all.stream().map(ProjectionStrategy::name).toList())
                .containsExactly("DETERMINISTIC", "STRESS", "MONTE_CARLO");
    }
}
