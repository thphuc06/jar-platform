package io.github.thphuc06.jars.goals.internal.simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The straight line: the amount grows by the expected monthly net every month, never below zero. This is the legacy
 * {@code projection_engine.py} ({@code max(0, start + capacity * month)}) for every month instead of a few checkpoints.
 * Exact {@link BigDecimal} arithmetic, no randomness.
 */
public final class DeterministicProjection implements ProjectionStrategy {

    public static final String NAME = "DETERMINISTIC";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProjectionOutcome project(ProjectionInput input) {
        return line(NAME, input, input.monthlyMean());
    }

    /** The line for a given monthly net; shared with the stress projection. */
    static ProjectionOutcome line(String name, ProjectionInput input, BigDecimal monthlyNet) {
        List<PathPoint> path = new ArrayList<>();
        BigDecimal amount = input.startingAmount().max(BigDecimal.ZERO);
        path.add(point(0, amount));
        for (int month = 1; month <= input.months(); month++) {
            amount = input.startingAmount()
                    .add(monthlyNet.multiply(BigDecimal.valueOf(month)))
                    .max(BigDecimal.ZERO);
            path.add(point(month, amount));
        }
        BigDecimal end = path.getLast().p50();
        boolean reaches = end.compareTo(input.targetAmount()) >= 0;
        return new ProjectionOutcome(
                name,
                end,
                end,
                end,
                reaches ? BigDecimal.ONE.setScale(4) : BigDecimal.ZERO.setScale(4),
                reaches,
                List.copyOf(path));
    }

    private static PathPoint point(int month, BigDecimal amount) {
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_EVEN);
        return new PathPoint(month, rounded, rounded, rounded);
    }
}
