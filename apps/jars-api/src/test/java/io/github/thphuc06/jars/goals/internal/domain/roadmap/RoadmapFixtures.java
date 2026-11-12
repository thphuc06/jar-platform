package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PlannerState;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.SavingsCapacity;
import java.math.BigDecimal;

/** Planner states for the roadmap tests, including the one of the legacy UI's mock roadmap. */
final class RoadmapFixtures {

    private RoadmapFixtures() {}

    private static BigDecimal d(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    static PlannerState state(
            String cashflow,
            String capacity,
            String capacityLabel,
            String runway,
            String liquidity,
            String anomaly,
            String readiness,
            String hint,
            String risk,
            String progress,
            String buffer) {
        return new PlannerState(
                cashflow,
                new SavingsCapacity(d(capacity), capacityLabel),
                d(runway),
                liquidity,
                anomaly,
                readiness,
                hint,
                risk,
                d(progress),
                buffer,
                "test");
    }

    /**
     * The planner state of {@code ui/src/lib/mock-roadmap.ts}: negative cashflow, an anomaly, 68.78 months of runway, 6.3%
     * of a 1.5 billion goal saved.
     */
    static PlannerState legacyMock() {
        return state(
                "negative",
                "-1609776.54",
                "none",
                "68.78",
                "low",
                "active",
                "cautious",
                "low",
                "moderate",
                "0.06292",
                "present");
    }

    /** A user with a surplus, a long runway and no anomaly. */
    static PlannerState healthy() {
        return state(
                "positive",
                "9000000.00",
                "strong",
                "9.10",
                "low",
                "none",
                "ready",
                "high",
                "low",
                "0.40000",
                "present");
    }

    /** Nothing is known: a user without history. */
    static PlannerState unknown() {
        return state(
                "unknown", null, "unknown", null, "unknown", "none", "unknown", "unknown", "unknown", "0.00000",
                "missing");
    }
}
