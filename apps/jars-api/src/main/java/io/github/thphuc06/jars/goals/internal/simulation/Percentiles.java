package io.github.thphuc06.jars.goals.internal.simulation;

import java.util.Arrays;

/**
 * Percentiles with linear interpolation between the two nearest values, the definition of the legacy
 * {@code planner_agent/finance/common.py:percentile} (so {@code percentile([1, 2, 3, 4], 0.5) == 2.5}).
 */
final class Percentiles {

    private Percentiles() {}

    /** @param q the quantile, 0 to 1 (values outside are clamped) */
    static double of(double[] values, double q) {
        if (values.length == 0) {
            throw new IllegalArgumentException("no values");
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return ofSorted(sorted, q);
    }

    static double ofSorted(double[] sorted, double q) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double clamped = Math.max(0.0, Math.min(1.0, q));
        double position = clamped * (sorted.length - 1);
        int lower = (int) position;
        int upper = Math.min(sorted.length - 1, lower + 1);
        double weight = position - lower;
        return sorted[lower] * (1 - weight) + sorted[upper] * weight;
    }
}
