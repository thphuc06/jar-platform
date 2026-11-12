package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.ACCUMULATE;
import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.MATURITY_TRANSITION;
import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.PROTECT_LIQUIDITY;
import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.READINESS_REVIEW;
import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.STABILIZE;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PlannerState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Chooses the stages of the roadmap and the one the user is in. In the legacy backend a language model proposed several
 * stage sequences, a validator ({@code candidate_validator.py}) rejected the ones that broke hard rules, and a ranker
 * picked one. Here the rules produce the sequence directly, so the hard rules hold by construction (and the tests check
 * that they do):
 *
 * <ul>
 *   <li>an open anomaly, or a runway under 3 months, means the sequence starts by protecting liquidity (legacy:
 *       {@code active_anomaly_ignored}, {@code low_runway_without_protect_liquidity});
 *   <li>a pace that leaves no surplus means stabilising comes before accumulating (legacy:
 *       {@code negative_cashflow_without_early_stabilization}, {@code non_positive_capacity_with_immediate_accumulate});
 *   <li>accumulate and review always follow, and the sequence always ends with the maturity transition (legacy
 *       {@code compile_roadmap}).
 * </ul>
 *
 * The current stage is the legacy {@code _determine_current_phase}, unchanged.
 */
public final class JourneyPlanner {

    private static final List<String> TIGHT_LIQUIDITY = Arrays.asList("critical", "high");
    private static final List<String> WEAK_READINESS = Arrays.asList("not_ready", "cautious");

    private JourneyPlanner() {}

    /**
     * @param pattern the stages joined with {@code _then_}, without the maturity transition, for example
     *     {@code protect_then_stabilize_then_accumulate_then_review}
     */
    public record Journey(List<PhaseType> sequence, String pattern, PhaseType current) {}

    /** @param goalReached the funding jar already holds the target: the journey is at its end */
    public static Journey plan(PlannerState planner, boolean goalReached) {
        List<PhaseType> sequence = new ArrayList<>();
        if (needsProtection(planner)) {
            sequence.add(PROTECT_LIQUIDITY);
        }
        if (needsStabilizing(planner)) {
            sequence.add(STABILIZE);
        }
        sequence.add(ACCUMULATE);
        sequence.add(READINESS_REVIEW);
        sequence.add(MATURITY_TRANSITION);

        String pattern = sequence.stream()
                .filter(p -> p != MATURITY_TRANSITION)
                .map(PhaseType::shortName)
                .collect(Collectors.joining("_then_"));
        PhaseType current = goalReached ? MATURITY_TRANSITION : currentPhase(planner, sequence);
        return new Journey(List.copyOf(sequence), pattern, current);
    }

    static boolean needsProtection(PlannerState planner) {
        return "active".equals(planner.anomalyState()) || TIGHT_LIQUIDITY.contains(planner.liquidityPressure());
    }

    static boolean needsStabilizing(PlannerState planner) {
        boolean noSurplus = planner.savingsCapacity() != null
                && planner.savingsCapacity().amountMonthly() != null
                && planner.savingsCapacity().amountMonthly().signum() <= 0;
        return noSurplus || "negative".equals(planner.cashflowStatus()) || "flat".equals(planner.cashflowStatus());
    }

    /** Legacy {@code _determine_current_phase}. */
    static PhaseType currentPhase(PlannerState planner, List<PhaseType> sequence) {
        if ("active".equals(planner.anomalyState()) && sequence.contains(PROTECT_LIQUIDITY)) {
            return PROTECT_LIQUIDITY;
        }
        if (TIGHT_LIQUIDITY.contains(planner.liquidityPressure()) && sequence.contains(PROTECT_LIQUIDITY)) {
            return PROTECT_LIQUIDITY;
        }
        if ("negative".equals(planner.cashflowStatus()) && sequence.contains(STABILIZE)) {
            return STABILIZE;
        }
        if (WEAK_READINESS.contains(planner.readinessLabel()) && sequence.contains(STABILIZE)) {
            return STABILIZE;
        }
        return sequence.contains(ACCUMULATE) ? ACCUMULATE : sequence.getFirst();
    }

    /** {@code completed} before the current stage, {@code current}, then {@code upcoming}. */
    public static String statusOf(PhaseType phase, List<PhaseType> sequence, PhaseType current) {
        int index = sequence.indexOf(phase);
        int currentIndex = sequence.indexOf(current);
        return index < currentIndex ? "completed" : index == currentIndex ? "current" : "upcoming";
    }
}
