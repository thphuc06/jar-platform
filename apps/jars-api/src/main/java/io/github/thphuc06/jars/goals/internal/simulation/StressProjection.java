package io.github.thphuc06.jars.goals.internal.simulation;

/**
 * The cautious line: every month comes out one standard deviation worse than the average, for the whole horizon. It is
 * not a forecast but a stress test: if even this reaches the target, the plan does not depend on a good run of months.
 */
public final class StressProjection implements ProjectionStrategy {

    public static final String NAME = "STRESS";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ProjectionOutcome project(ProjectionInput input) {
        return DeterministicProjection.line(NAME, input, input.monthlyMean().subtract(input.monthlyStdDev()));
    }
}
