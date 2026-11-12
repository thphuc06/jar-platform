package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PhaseData;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PlannerState;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.Projection;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.ProjectionPoint;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.StatusBanner;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.UnlockCondition;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class VisualizationMapperTest {

    private static PlannerState state(
            String cashflow,
            String capacity,
            String runway,
            String liquidity,
            String anomaly,
            String hint,
            String buffer) {
        return RoadmapFixtures.state(
                cashflow, capacity, "x", runway, liquidity, anomaly, "cautious", hint, "moderate", "0.1", buffer);
    }

    private static List<PhaseData> phases(PhaseType current) {
        List<PhaseType> sequence = List.of(
                PhaseType.PROTECT_LIQUIDITY,
                PhaseType.STABILIZE,
                PhaseType.ACCUMULATE,
                PhaseType.READINESS_REVIEW,
                PhaseType.MATURITY_TRANSITION);
        return sequence.stream()
                .map(p -> new PhaseData(
                        p.wire(),
                        p.title(),
                        p.objective(),
                        p.entryConditions(),
                        p.exitConditions(),
                        p.expectedResults(),
                        p.serviceIds(),
                        JourneyPlanner.statusOf(p, sequence, current)))
                .toList();
    }

    private static Projection projection(String... amounts) {
        List<ProjectionPoint> series = new java.util.ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            series.add(new ProjectionPoint(i * 12, new BigDecimal(amounts[i])));
        }
        return new Projection("cautious", "basis", new BigDecimal("1000"), null, 36, series, "caution");
    }

    // ------------------------------------------------------------------ status banner

    @Test
    void theLegacyMockState_isACautiousBanner_thatMentionsTheCushion() {
        StatusBanner banner = VisualizationMapper.statusBanner(
                RoadmapFixtures.legacyMock(), phases(PhaseType.PROTECT_LIQUIDITY), "protect_liquidity");

        assertThat(banner.tone()).isEqualTo("cautious"); // as in the mock
        assertThat(banner.title()).isEqualTo("Bảo vệ dòng tiền trước khi dồn sức cho mục tiêu");
        assertThat(banner.message()).contains("bảo vệ thanh khoản trước").contains("Bạn vẫn còn đệm từ quỹ đang có");
    }

    @Test
    void tightLiquidity_orAShortRunway_isUrgent() {
        for (PlannerState planner : List.of(
                state("positive", "5000000", "6", "critical", "none", "high", "present"),
                state("positive", "5000000", "6", "high", "none", "high", "present"),
                state("positive", "5000000", "2.5", "low", "none", "high", "present"))) {
            StatusBanner banner =
                    VisualizationMapper.statusBanner(planner, phases(PhaseType.PROTECT_LIQUIDITY), "protect_liquidity");

            assertThat(banner.tone()).isEqualTo("urgent");
            assertThat(banner.title()).isEqualTo("Củng cố nền tảng trước khi dồn sức cho mục tiêu");
        }
    }

    @Test
    void urgencyWinsOverTheOtherWarnings() {
        PlannerState planner = state("negative", "-1", "0.5", "critical", "active", "low", "present");

        assertThat(VisualizationMapper.statusBanner(planner, phases(PhaseType.PROTECT_LIQUIDITY), "protect_liquidity")
                        .tone())
                .isEqualTo("urgent");
    }

    @Test
    void anAnomaly_aNegativePace_orAGoalThatIsAStretch_isCautious() {
        List<PlannerState> warnings = List.of(
                state("positive", "5000000", "9", "low", "active", "high", "present"),
                state("negative", "-1", "9", "low", "none", "high", "present"),
                state("positive", "-1", "9", "low", "none", "high", "present"), // capacity below zero is enough
                state("positive", "5000000", "9", "low", "none", "low", "present"),
                state("positive", "5000000", "9", "low", "none", "stretch", "present"));
        for (PlannerState planner : warnings) {
            assertThat(VisualizationMapper.statusBanner(planner, phases(PhaseType.STABILIZE), "stabilize")
                            .tone())
                    .isEqualTo("cautious");
        }
    }

    @Test
    void theCushionClause_dependsOnTheBufferAndTheRunway() {
        PlannerState withBuffer = state("negative", "-1", "4", "medium", "none", "high", "present");
        PlannerState longRunwayNoBuffer = state("negative", "-1", "7", "low", "none", "high", "missing");
        PlannerState neither = state("negative", "-1", "4", "medium", "none", "high", "missing");
        PlannerState unknownRunway = state("negative", "-1", null, "unknown", "none", "high", "missing");

        assertThat(VisualizationMapper.statusBanner(withBuffer, phases(PhaseType.STABILIZE), "stabilize")
                        .message())
                .contains("quỹ đang có");
        assertThat(VisualizationMapper.statusBanner(longRunwayNoBuffer, phases(PhaseType.STABILIZE), "stabilize")
                        .message())
                .contains("thời gian dự phòng");
        assertThat(VisualizationMapper.statusBanner(neither, phases(PhaseType.STABILIZE), "stabilize")
                        .message())
                .doesNotContain("đệm");
        assertThat(VisualizationMapper.statusBanner(unknownRunway, phases(PhaseType.STABILIZE), "stabilize")
                        .message())
                .doesNotContain("đệm");
    }

    @Test
    void aHealthyUserWhoIsAccumulating_isEncouraged() {
        StatusBanner banner =
                VisualizationMapper.statusBanner(RoadmapFixtures.healthy(), phases(PhaseType.ACCUMULATE), "accumulate");

        assertThat(banner.tone()).isEqualTo("encouraging");
    }

    @Test
    void aHealthyUserInAnotherStage_getsASupportiveBanner() {
        StatusBanner banner = VisualizationMapper.statusBanner(
                RoadmapFixtures.healthy(), phases(PhaseType.READINESS_REVIEW), "readiness_review");

        assertThat(banner.tone()).isEqualTo("supportive");
    }

    // ------------------------------------------------------------------ trend and unlock conditions

    @Test
    void trend_comparesTheEndOfTheSeriesWithItsStart_withAToleranceOfOne() {
        assertThat(VisualizationMapper.trend(projection("100", "200"))).isEqualTo("improving");
        assertThat(VisualizationMapper.trend(projection("100", "50"))).isEqualTo("declining");
        assertThat(VisualizationMapper.trend(projection("100", "101"))).isEqualTo("flat");
        assertThat(VisualizationMapper.trend(projection("100", "99"))).isEqualTo("flat");
        assertThat(VisualizationMapper.trend(projection("100", "101.01"))).isEqualTo("improving");
        assertThat(VisualizationMapper.trend(projection("100", "98.99"))).isEqualTo("declining");
        assertThat(VisualizationMapper.trend(projection("100"))).as("one point").isEqualTo("flat");
        assertThat(VisualizationMapper.trend(projection())).as("no points").isEqualTo("flat");
    }

    @Test
    void unlockConditions_areTheExitOfTheCurrentStageAndTheEntryOfTheNext() {
        List<PhaseData> phases = phases(PhaseType.STABILIZE);

        List<UnlockCondition> conditions = VisualizationMapper.unlockConditions(phases);

        assertThat(conditions).hasSize(2);
        assertThat(conditions.get(0).phaseType()).isEqualTo("stabilize");
        assertThat(conditions.get(0).status()).isEqualTo("current");
        assertThat(conditions.get(0).conditions()).isEqualTo(PhaseType.STABILIZE.exitConditions());
        assertThat(conditions.get(1).phaseType()).isEqualTo("accumulate");
        assertThat(conditions.get(1).status()).isEqualTo("next");
        assertThat(conditions.get(1).conditions()).isEqualTo(PhaseType.ACCUMULATE.entryConditions());
    }

    @Test
    void unlockConditions_inTheLastStage_haveNoNext() {
        List<UnlockCondition> conditions = VisualizationMapper.unlockConditions(phases(PhaseType.MATURITY_TRANSITION));

        assertThat(conditions).hasSize(1);
        assertThat(conditions.getFirst().status()).isEqualTo("current");
    }

    @Test
    void unlockConditions_ofNoPhases_areEmpty() {
        assertThat(VisualizationMapper.unlockConditions(List.of())).isEmpty();
    }
}
