package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.MaturityEvent;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.Projection;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.ProjectionPoint;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProjectionEngineTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static List<Integer> months(Projection p) {
        return p.series().stream().map(ProjectionPoint::month).toList();
    }

    private static List<String> amounts(Projection p) {
        return p.series().stream().map(point -> point.projectedAmount().toPlainString()).toList();
    }

    @Test
    void theLegacyMockRoadmap_isReproducedToTheCent() {
        // ui/src/lib/mock-roadmap.ts: 94,380,000 saved of 1.5 billion, 36 months, a capacity of about -1,609,776.54 a
        // month. The series there is 94,380,000 / 75,062,681.56 / 55,745,363.13 / 36,428,044.69, so the unrounded capacity
        // is (94,380,000 - 36,428,044.69) / 36.
        BigDecimal capacity = new BigDecimal("-1609776.536388888888888888889");

        Projection p = ProjectionEngine.build(d("1500000000"), 36, capacity, d("94380000"), "ready", "low");

        assertThat(months(p)).containsExactly(0, 12, 24, 36);
        assertThat(amounts(p)).containsExactly("94380000.00", "75062681.56", "55745363.13", "36428044.69");
        assertThat(p.projectedEndAmount()).isEqualByComparingTo("36428044.69");
        assertThat(p.targetAmount()).isEqualByComparingTo("1500000000");
        assertThat(p.targetTimelineMonths()).isEqualTo(36);
        assertThat(p.confidenceLabel()).isEqualTo("cautious"); // the mock says cautious too
        assertThat(p.basis()).isEqualTo("planner_state_monthly_capacity");
    }

    @Test
    void theLegacyServiceAgentTestInput_giveAStraightClimbThatFallsShort() {
        // tests/test_service_agent_mvp.py: a car, 60,000,000 in 12 months, capacity 4,000,000, nothing saved yet
        Projection p = ProjectionEngine.build(d("60000000"), 12, d("4000000"), BigDecimal.ZERO, "ready", "medium");

        assertThat(months(p)).containsExactly(0, 4, 8, 12);
        assertThat(amounts(p)).containsExactly("0.00", "16000000.00", "32000000.00", "48000000.00");
        assertThat(p.projectedEndAmount()).isEqualByComparingTo("48000000");
        assertThat(p.confidenceLabel()).isEqualTo("directional");
    }

    @ParameterizedTest(name = "timeline {0} months -> checkpoints {1}")
    @CsvSource(delimiter = '|', value = {
        "1|0,1", // 0, 1, 1, 1: coinciding checkpoints are listed once
        "2|0,1,2",
        "3|0,1,2,3",
        "4|0,1,2,4",
        "6|0,2,4,6",
        "10|0,3,6,10",
        "36|0,12,24,36"
    })
    void checkpoints_areZeroThirdTwoThirdsAndTheEnd_withoutDuplicates(int timeline, String expected) {
        Projection p = ProjectionEngine.build(d("1000"), timeline, d("10"), BigDecimal.ZERO, "ready", "high");

        assertThat(months(p)).containsExactlyElementsOf(
                java.util.Arrays.stream(expected.split(",")).map(Integer::valueOf).toList());
    }

    @Test
    void aDecliningPace_stopsAtZero() {
        Projection p = ProjectionEngine.build(d("1000000"), 12, d("-300000"), d("1000000"), "ready", "low");

        assertThat(amounts(p)).containsExactly("1000000.00", "0.00", "0.00", "0.00");
        assertThat(p.projectedEndAmount()).isEqualByComparingTo("0");
    }

    @Test
    void theAmountsKeepTwoDecimals_withBankersRounding() {
        Projection p = ProjectionEngine.build(d("100"), 3, d("0.005"), BigDecimal.ZERO, "ready", "high");

        // months 0, 1, 2, 3: 0, 0.005, 0.010, 0.015 -> 0.00, 0.00 (half even), 0.01, 0.02 (half even)
        assertThat(amounts(p)).containsExactly("0.00", "0.00", "0.01", "0.02");
    }

    @ParameterizedTest(name = "{0} readiness, hint {1} -> {2}")
    @CsvSource({
        "ready, high, directional",
        "ready, medium, directional",
        "ready, stretch, cautious",
        "ready, low, cautious",
        "ready, unknown, cautious",
        "partial_but_usable, high, provisional",
        "insufficient_financial_state, high, provisional",
        "insufficient_goal_data, medium, provisional"
    })
    void confidence_followsTheReadinessAndTheFeasibility(String readiness, String hint, String expected) {
        Projection p = ProjectionEngine.build(d("1000"), 12, d("10"), BigDecimal.ZERO, readiness, hint);

        assertThat(p.confidenceLabel()).isEqualTo(expected);
    }

    @Test
    void withoutAUsablePace_theProjectionIsEmptyAndProvisional() {
        Projection noCapacity = ProjectionEngine.build(d("1000"), 12, null, BigDecimal.ZERO, "ready", "high");
        Projection noTimeline = ProjectionEngine.build(d("1000"), 0, d("10"), BigDecimal.ZERO, "ready", "high");
        Projection noTarget = ProjectionEngine.build(BigDecimal.ZERO, 12, d("10"), BigDecimal.ZERO, "ready", "high");

        for (Projection p : List.of(noCapacity, noTimeline, noTarget)) {
            assertThat(p.confidenceLabel()).isEqualTo("provisional");
            assertThat(p.basis()).isEqualTo("missing_target_or_capacity");
            assertThat(p.series()).isEmpty();
            assertThat(p.projectedEndAmount()).isNull();
        }
    }

    @Test
    void maturityEvents_sayWhetherTheProjectionReachesTheTarget() {
        MaturityEvent reaches = ProjectionEngine.maturityEvents(d("1000"), d("1000")).getFirst();
        MaturityEvent falls = ProjectionEngine.maturityEvents(d("1000"), d("999.99")).getFirst();
        MaturityEvent unknown = ProjectionEngine.maturityEvents(d("1000"), null).getFirst();
        MaturityEvent noTarget = ProjectionEngine.maturityEvents(null, d("5")).getFirst();

        assertThat(reaches.eventType()).isEqualTo("goal_ready_for_execution");
        assertThat(falls.eventType()).isEqualTo("readiness_review_required");
        assertThat(unknown.eventType()).isEqualTo("readiness_review_required");
        assertThat(noTarget.eventType()).isEqualTo("readiness_review_required");
        assertThat(reaches.title()).isNotBlank();
        assertThat(falls.condition()).isNotBlank();
        assertThat(ProjectionEngine.maturityEvents(d("1000"), d("1000"))).hasSize(1);
    }
}
