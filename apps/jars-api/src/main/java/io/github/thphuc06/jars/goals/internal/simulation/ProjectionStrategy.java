package io.github.thphuc06.jars.goals.internal.simulation;

import java.math.BigDecimal;
import java.util.List;

/**
 * One way to project the amount saved for a goal month by month. The variants (a straight line, a stress case, a Monte
 * Carlo simulation) all answer the same question with the same input and the same output type, so the services can ask
 * for any of them, or for all of them, without knowing how each works (the strategy pattern).
 *
 * <p>This package works with {@code double}: random draws and percentiles are floating-point maths. Money enters as
 * {@link BigDecimal} and leaves as {@link BigDecimal} rounded to two decimals, and nothing here is stored.
 */
public interface ProjectionStrategy {

    /** A short stable name, used as the key in API responses. */
    String name();

    ProjectionOutcome project(ProjectionInput input);

    /**
     * @param startingAmount what is saved now
     * @param targetAmount what the goal needs
     * @param months how many months to look ahead, at least 1
     * @param monthlyMean the expected net saved per month; negative when the user spends more than they earn
     * @param monthlyStdDev how much the net varies from month to month, at least 0
     * @param seed seeds the random generator of the simulation, so the same input always gives the same result
     */
    record ProjectionInput(
            BigDecimal startingAmount,
            BigDecimal targetAmount,
            int months,
            BigDecimal monthlyMean,
            BigDecimal monthlyStdDev,
            long seed) {

        public ProjectionInput {
            if (months < 1) {
                throw new IllegalArgumentException("months must be at least 1");
            }
            if (monthlyStdDev.signum() < 0) {
                throw new IllegalArgumentException("monthlyStdDev must not be negative");
            }
        }

        public ProjectionInput withMean(BigDecimal newMean) {
            return new ProjectionInput(startingAmount, targetAmount, months, newMean, monthlyStdDev, seed);
        }
    }

    /** The amount after one month: the 10th, 50th and 90th percentile (all equal for a deterministic projection). */
    record PathPoint(int month, BigDecimal p10, BigDecimal p50, BigDecimal p90) {}

    /**
     * @param p10 amount at the end of the horizon, pessimistic
     * @param p50 amount at the end of the horizon, typical
     * @param p90 amount at the end of the horizon, optimistic
     * @param probabilityOfSuccess share of outcomes at or above the target, 0 to 1 with 4 decimals; a deterministic
     *     projection answers 0 or 1
     * @param path one point per month from 0 (now) to the horizon
     */
    record ProjectionOutcome(
            String strategy,
            BigDecimal p10,
            BigDecimal p50,
            BigDecimal p90,
            BigDecimal probabilityOfSuccess,
            boolean reachesTarget,
            List<PathPoint> path) {}
}
