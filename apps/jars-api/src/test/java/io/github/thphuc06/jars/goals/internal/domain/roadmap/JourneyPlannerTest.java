package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.ACCUMULATE;
import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.MATURITY_TRANSITION;
import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.PROTECT_LIQUIDITY;
import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.READINESS_REVIEW;
import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.STABILIZE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.JourneyPlanner.Journey;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PlannerState;
import java.util.List;
import org.junit.jupiter.api.Test;

class JourneyPlannerTest {

    private static final String[] CASHFLOW = {"unknown", "positive", "flat", "negative"};
    private static final String[] CAPACITY = {null, "-1500000", "0", "3000000"};
    private static final String[] LIQUIDITY = {"unknown", "critical", "high", "medium", "low"};
    private static final String[] ANOMALY = {"none", "active"};
    private static final String[] READINESS = {"unknown", "ready", "cautious", "not_ready"};

    private static PlannerState state(
            String cashflow, String capacity, String liquidity, String anomaly, String readiness) {
        return RoadmapFixtures.state(cashflow, capacity, "x", "6", liquidity, anomaly, readiness, "medium", "low", "0", "present");
    }

    @Test
    void theLegacyMockState_getsTheMockJourney() {
        Journey journey = JourneyPlanner.plan(RoadmapFixtures.legacyMock(), false);

        assertThat(journey.sequence())
                .containsExactly(PROTECT_LIQUIDITY, STABILIZE, ACCUMULATE, READINESS_REVIEW, MATURITY_TRANSITION);
        assertThat(journey.pattern()).isEqualTo("protect_then_stabilize_then_accumulate_then_review");
        assertThat(journey.current()).isEqualTo(PROTECT_LIQUIDITY);
    }

    @Test
    void aHealthyState_goesStraightToAccumulating() {
        Journey journey = JourneyPlanner.plan(RoadmapFixtures.healthy(), false);

        assertThat(journey.sequence()).containsExactly(ACCUMULATE, READINESS_REVIEW, MATURITY_TRANSITION);
        assertThat(journey.pattern()).isEqualTo("accumulate_then_review");
        assertThat(journey.current()).isEqualTo(ACCUMULATE);
    }

    @Test
    void anAnomalyWithASurplus_protectsFirstThenAccumulates() {
        PlannerState planner = state("positive", "3000000", "low", "active", "cautious");

        Journey journey = JourneyPlanner.plan(planner, false);

        assertThat(journey.pattern()).isEqualTo("protect_then_accumulate_then_review");
        assertThat(journey.current()).isEqualTo(PROTECT_LIQUIDITY);
    }

    @Test
    void aNegativePaceWithoutOtherProblems_stabilizesFirst() {
        PlannerState planner = state("negative", "-1500000", "low", "none", "cautious");

        Journey journey = JourneyPlanner.plan(planner, false);

        assertThat(journey.pattern()).isEqualTo("stabilize_then_accumulate_then_review");
        assertThat(journey.current()).isEqualTo(STABILIZE);
    }

    @Test
    void aFlatPace_alsoNeedsStabilizing() {
        PlannerState planner = state("flat", "0", "low", "none", "cautious");

        Journey journey = JourneyPlanner.plan(planner, false);

        assertThat(journey.sequence().getFirst()).isEqualTo(STABILIZE);
        assertThat(journey.current()).isEqualTo(STABILIZE);
    }

    @Test
    void tightLiquidity_protectsFirst() {
        for (String liquidity : List.of("critical", "high")) {
            Journey journey = JourneyPlanner.plan(state("positive", "3000000", liquidity, "none", "cautious"), false);

            assertThat(journey.sequence().getFirst()).as(liquidity).isEqualTo(PROTECT_LIQUIDITY);
            assertThat(journey.current()).isEqualTo(PROTECT_LIQUIDITY);
        }
    }

    @Test
    void theCurrentStage_followsTheLegacyRulesInOrder() {
        List<PhaseType> full = List.of(PROTECT_LIQUIDITY, STABILIZE, ACCUMULATE, READINESS_REVIEW, MATURITY_TRANSITION);

        // legacy test_milestones_follow_phase_progress_statuses: no anomaly, medium liquidity, negative cashflow and a
        // cautious readiness put the user in "stabilize" even though the sequence starts with "protect"
        assertThat(JourneyPlanner.currentPhase(state("negative", "-1", "medium", "none", "cautious"), full))
                .isEqualTo(STABILIZE);
        assertThat(JourneyPlanner.currentPhase(state("negative", "-1", "medium", "active", "cautious"), full))
                .isEqualTo(PROTECT_LIQUIDITY);
        assertThat(JourneyPlanner.currentPhase(state("negative", "-1", "high", "none", "cautious"), full))
                .isEqualTo(PROTECT_LIQUIDITY);
        assertThat(JourneyPlanner.currentPhase(state("positive", "1", "medium", "none", "not_ready"), full))
                .isEqualTo(STABILIZE);
        assertThat(JourneyPlanner.currentPhase(state("positive", "1", "medium", "none", "ready"), full))
                .isEqualTo(ACCUMULATE);
        assertThat(JourneyPlanner.currentPhase(state("positive", "1", "low", "none", "ready"), List.of(READINESS_REVIEW)))
                .as("no accumulate in the sequence: the first stage")
                .isEqualTo(READINESS_REVIEW);
        assertThat(JourneyPlanner.currentPhase(state("negative", "-1", "low", "active", "cautious"), List.of(ACCUMULATE)))
                .as("protect and stabilize are not in this sequence")
                .isEqualTo(ACCUMULATE);
    }

    @Test
    void aReachedGoal_isAtTheEndOfTheJourney() {
        Journey journey = JourneyPlanner.plan(RoadmapFixtures.legacyMock(), true);

        assertThat(journey.current()).isEqualTo(MATURITY_TRANSITION);
        assertThat(journey.sequence().stream()
                        .map(p -> JourneyPlanner.statusOf(p, journey.sequence(), journey.current()))
                        .toList())
                .containsExactly("completed", "completed", "completed", "completed", "current");
    }

    @Test
    void statusOf_marksTheStagesBeforeAsCompleted_andAfterAsUpcoming() {
        List<PhaseType> sequence = List.of(PROTECT_LIQUIDITY, STABILIZE, ACCUMULATE, READINESS_REVIEW, MATURITY_TRANSITION);

        assertThat(sequence.stream()
                        .map(p -> JourneyPlanner.statusOf(p, sequence, STABILIZE))
                        .toList())
                .containsExactly("completed", "current", "upcoming", "upcoming", "upcoming");
    }

    /**
     * The hard rules of the legacy candidate validator (candidate_validator.py), checked over every combination of the
     * signals instead of for a few examples: whatever the state, the planner never produces a journey the legacy
     * validator would have rejected.
     */
    @Test
    void theLegacyHardRules_holdForEveryCombinationOfSignals() {
        int checked = 0;
        for (String cashflow : CASHFLOW) {
            for (String capacity : CAPACITY) {
                for (String liquidity : LIQUIDITY) {
                    for (String anomaly : ANOMALY) {
                        for (String readiness : READINESS) {
                            PlannerState planner = state(cashflow, capacity, liquidity, anomaly, readiness);
                            for (boolean reached : new boolean[] {false, true}) {
                                Journey journey = JourneyPlanner.plan(planner, reached);
                                List<PhaseType> seq = journey.sequence();
                                String where = cashflow + "/" + capacity + "/" + liquidity + "/" + anomaly + "/"
                                        + readiness + "/" + reached;

                                // always accumulate, then review, then the maturity transition, in that order
                                assertThat(seq.getLast()).as(where).isEqualTo(MATURITY_TRANSITION);
                                assertThat(seq.indexOf(ACCUMULATE)).as(where).isLessThan(seq.indexOf(READINESS_REVIEW));
                                assertThat(seq.indexOf(READINESS_REVIEW))
                                        .as(where)
                                        .isLessThan(seq.indexOf(MATURITY_TRANSITION));
                                assertThat(seq).as(where).doesNotHaveDuplicates().contains(journey.current());

                                // active_anomaly_ignored: an anomaly means protect liquidity, and before anything else
                                if ("active".equals(anomaly)) {
                                    assertThat(seq.getFirst()).as(where).isEqualTo(PROTECT_LIQUIDITY);
                                }
                                // low_runway_without_protect_liquidity: under 3 months of runway means protect first
                                if (List.of("critical", "high").contains(liquidity)) {
                                    assertThat(seq.getFirst()).as(where).isEqualTo(PROTECT_LIQUIDITY);
                                }
                                // negative_cashflow_without_early_stabilization
                                if ("negative".equals(cashflow)) {
                                    assertThat(seq.subList(0, 2))
                                            .as(where)
                                            .containsAnyOf(STABILIZE, PROTECT_LIQUIDITY);
                                }
                                // non_positive_capacity_with_immediate_accumulate
                                if (capacity != null && Long.parseLong(capacity) <= 0) {
                                    assertThat(seq.getFirst()).as(where).isNotEqualTo(ACCUMULATE);
                                }
                                // the pattern names the stages before the maturity transition
                                assertThat(journey.pattern().split("_then_"))
                                        .as(where)
                                        .hasSize(seq.size() - 1);
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(4 * 4 * 5 * 2 * 4 * 2);
    }
}
