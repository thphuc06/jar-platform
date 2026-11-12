package io.github.thphuc06.jars.goals.internal.simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Monte Carlo over the spread of the monthly net: every draw walks the horizon month by month, adding a normally
 * distributed net {@code N(mean, stdDev)} each month and never going below zero (savings are not spent into debt in the
 * plan). The result is the distribution of the amount at the end, and for every month, over all draws.
 *
 * <p>Ported from {@code goal.py} / {@code scenario.py} of the legacy backend: 2,000 draws, a Gaussian per month, the
 * probability of success being the share of draws that reach the target. What changed: the legacy code drew around a
 * forecast per month whose spread came from a forecasting library's p10/p90 interval; here the mean and spread are the
 * observed average and standard deviation of the user's monthly net, because that is the history this system has.
 *
 * <p><b>Reproducible by construction:</b> the generator is {@code L64X128MixRandom} created from the seed of the input,
 * and every draw consumes exactly one Gaussian per month whatever the mean is. The same input therefore always gives the
 * same output, a different seed gives a different one, and two inputs that differ only in the mean see the very same
 * random numbers (common random numbers), so the difference between a scenario and its base is not blurred by noise.
 */
public final class MonteCarloProjection implements ProjectionStrategy {

    public static final String NAME = "MONTE_CARLO";
    public static final int DEFAULT_DRAWS = 2_000;
    private static final String ALGORITHM = "L64X128MixRandom";

    private final int draws;

    public MonteCarloProjection() {
        this(DEFAULT_DRAWS);
    }

    public MonteCarloProjection(int draws) {
        if (draws < 1) {
            throw new IllegalArgumentException("draws must be at least 1");
        }
        this.draws = draws;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProjectionOutcome project(ProjectionInput input) {
        int months = input.months();
        double mean = input.monthlyMean().doubleValue();
        double sigma = input.monthlyStdDev().doubleValue();
        double target = input.targetAmount().doubleValue();
        double start = Math.max(0.0, input.startingAmount().doubleValue());

        RandomGenerator random = RandomGeneratorFactory.of(ALGORITHM).create(input.seed());
        double[][] byMonth = new double[months + 1][draws];
        Arrays.fill(byMonth[0], start);
        int successes = 0;
        for (int draw = 0; draw < draws; draw++) {
            double amount = start;
            for (int month = 1; month <= months; month++) {
                amount = Math.max(0.0, amount + mean + sigma * random.nextGaussian());
                byMonth[month][draw] = amount;
            }
            if (amount >= target) {
                successes++;
            }
        }

        List<PathPoint> path = new ArrayList<>();
        for (int month = 0; month <= months; month++) {
            double[] sorted = byMonth[month];
            Arrays.sort(sorted);
            path.add(new PathPoint(
                    month,
                    money(Percentiles.ofSorted(sorted, 0.10)),
                    money(Percentiles.ofSorted(sorted, 0.50)),
                    money(Percentiles.ofSorted(sorted, 0.90))));
        }
        PathPoint end = path.getLast();
        BigDecimal probability =
                BigDecimal.valueOf(successes).divide(BigDecimal.valueOf(draws), 4, RoundingMode.HALF_EVEN);
        return new ProjectionOutcome(
                NAME,
                end.p10(),
                end.p50(),
                end.p90(),
                probability,
                end.p50().compareTo(input.targetAmount()) >= 0,
                List.copyOf(path));
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_EVEN);
    }
}
