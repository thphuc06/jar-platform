package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.ReadinessClassifier.Classification;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PlannerState;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.SavingsCapacity;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ReadinessClassifierTest {

    private static final BigDecimal TARGET = new BigDecimal("60000000");

    private static Classification classify(PlannerState planner) {
        return ReadinessClassifier.classify(TARGET, 12, planner);
    }

    @Test
    void everythingKnown_isReady() {
        Classification c = classify(RoadmapFixtures.healthy());

        assertThat(c.status()).isEqualTo(ReadinessClassifier.READY);
        assertThat(c.missingFields()).isEmpty();
        assertThat(c.needsSetupRoadmap()).isFalse();
    }

    @Test
    void theLegacyMockState_isReady() {
        assertThat(classify(RoadmapFixtures.legacyMock()).status()).isEqualTo(ReadinessClassifier.READY);
    }

    @Test
    void oneOrTwoUnknownFields_arePartialButUsable() {
        PlannerState oneUnknown = new PlannerState(
                "positive",
                new SavingsCapacity(new BigDecimal("4000000"), "moderate"),
                new BigDecimal("2.4"),
                "high",
                "active",
                "cautious",
                "unknown", // the feasibility is not known
                "moderate",
                BigDecimal.ZERO,
                "present",
                "test");
        PlannerState twoUnknown = new PlannerState(
                "positive",
                new SavingsCapacity(new BigDecimal("4000000"), "moderate"),
                new BigDecimal("2.4"),
                "high",
                "active",
                "unknown",
                "unknown",
                "moderate",
                BigDecimal.ZERO,
                "present",
                "test");

        Classification one = classify(oneUnknown);
        Classification two = classify(twoUnknown);

        assertThat(one.status()).isEqualTo(ReadinessClassifier.PARTIAL);
        assertThat(one.missingFields()).containsExactly("planner_state.feasibility_hint");
        assertThat(two.status()).isEqualTo(ReadinessClassifier.PARTIAL);
        assertThat(two.missingFields())
                .containsExactly("planner_state.readiness_label", "planner_state.feasibility_hint");
        assertThat(one.needsSetupRoadmap()).isFalse();
    }

    @Test
    void aUserWithoutHistory_isInsufficientFinancialState_andListsEveryMissingField() {
        Classification c = classify(RoadmapFixtures.unknown());

        assertThat(c.status()).isEqualTo(ReadinessClassifier.INSUFFICIENT_STATE);
        assertThat(c.needsSetupRoadmap()).isTrue();
        assertThat(c.missingFields())
                .containsExactly(
                        "planner_state.cashflow_status",
                        "planner_state.liquidity_pressure",
                        "planner_state.readiness_label",
                        "planner_state.feasibility_hint",
                        "planner_state.risk_band",
                        "planner_state.runway_months",
                        "planner_state.savings_capacity.amount_monthly");
    }

    @Test
    void aGoalThatIsMissingTwoFields_isInsufficientGoalData() {
        Classification c = ReadinessClassifier.classify(BigDecimal.ZERO, 0, RoadmapFixtures.healthy());

        assertThat(c.status()).isEqualTo(ReadinessClassifier.INSUFFICIENT_GOAL);
        assertThat(c.missingFields()).containsExactly("goal.target_amount", "goal.target_timeline_months");
        assertThat(c.needsSetupRoadmap()).isTrue();
    }

    @Test
    void aGoalMissingOneField_withAKnownState_isPartial() {
        Classification c = ReadinessClassifier.classify(TARGET, 0, RoadmapFixtures.healthy());

        assertThat(c.status()).isEqualTo(ReadinessClassifier.PARTIAL);
        assertThat(c.missingFields()).containsExactly("goal.target_timeline_months");
    }

    @Test
    void aNullTarget_countsAsMissing() {
        Classification c = ReadinessClassifier.classify(null, 12, RoadmapFixtures.healthy());

        assertThat(c.missingFields()).containsExactly("goal.target_amount");
    }

    @Test
    void blankAndUppercaseUnknownLabels_areMissingToo() {
        PlannerState planner = new PlannerState(
                " ",
                new SavingsCapacity(new BigDecimal("1"), "low"),
                BigDecimal.ONE,
                "UNKNOWN",
                "none",
                null,
                "high",
                "low",
                BigDecimal.ZERO,
                "present",
                "test");

        Classification c = classify(planner);

        assertThat(c.missingFields())
                .containsExactly(
                        "planner_state.cashflow_status",
                        "planner_state.liquidity_pressure",
                        "planner_state.readiness_label");
    }

    @Test
    void aMissingSavingsCapacityObject_isMissingAmount() {
        PlannerState planner = new PlannerState(
                "positive",
                null,
                BigDecimal.ONE,
                "low",
                "none",
                "ready",
                "high",
                "low",
                BigDecimal.ZERO,
                "present",
                "test");

        assertThat(classify(planner).missingFields()).containsExactly("planner_state.savings_capacity.amount_monthly");
    }
}
