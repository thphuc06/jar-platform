package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapCompiler.RoadmapInput;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PhaseData;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.RoadmapContract;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RoadmapCompilerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    /** The goal of the legacy mock: 1.5 billion in 36 months, 94,380,000 saved, in the planner state of the mock. */
    private static RoadmapResponse mockRoadmap() {
        return RoadmapCompiler.compile(new RoadmapInput(
                "Mua nhà",
                d("1500000000"),
                36,
                d("94380000"),
                RoadmapFixtures.state(
                        "negative",
                        "-1609776.536388888888888888889",
                        "none",
                        "68.78",
                        "low",
                        "active",
                        "cautious",
                        "low",
                        "moderate",
                        "0.06292",
                        "present"),
                false));
    }

    // ------------------------------------------------------------------ a complete roadmap (the legacy mock)

    @Test
    void theMockGoal_getsTheMockRoadmap() {
        RoadmapResponse r = mockRoadmap();
        RoadmapContract c = r.roadmapContract();

        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.schemaVersion()).isEqualTo("v1");
        assertThat(c.status()).isEqualTo("ready");
        assertThat(c.missingFields()).isEmpty();
        assertThat(c.warnings()).isEmpty();
        assertThat(c.currentPhase()).isEqualTo("protect_liquidity");
        assertThat(c.phaseSequence())
                .containsExactly(
                        "protect_liquidity", "stabilize", "accumulate", "readiness_review", "maturity_transition");
        assertThat(c.journeyPattern()).isEqualTo("protect_then_stabilize_then_accumulate_then_review");
        assertThat(c.goal().goalType()).isEqualTo("home_purchase");
        assertThat(c.goal().targetAmount()).isEqualByComparingTo("1500000000");
        assertThat(c.goal().targetTimelineMonths()).isEqualTo(36);
        assertThat(c.phases())
                .extracting(PhaseData::status)
                .containsExactly("current", "upcoming", "upcoming", "upcoming", "upcoming");
        assertThat(c.milestones())
                .extracting(m -> m.milestoneId())
                .containsExactly("ms_1", "ms_2", "ms_3", "ms_4", "ms_5");
        assertThat(c.projection().series())
                .extracting(p -> p.projectedAmount().toPlainString())
                .containsExactly("94380000.00", "75062681.56", "55745363.13", "36428044.69");
        assertThat(c.projection().confidenceLabel()).isEqualTo("cautious");
        assertThat(c.nextBestAction()).isEqualTo(PhaseType.PROTECT_LIQUIDITY.nextAction());
        assertThat(c.maturityEvents().getFirst().eventType()).isEqualTo("readiness_review_required");

        assertThat(r.result().roadmapStatus()).isEqualTo("ready");
        assertThat(r.result().currentPhase()).isEqualTo("protect_liquidity");
        assertThat(r.result().phaseSequence()).isEqualTo(c.phaseSequence());
        assertThat(r.result().journeyPattern()).isEqualTo(c.journeyPattern());
    }

    @Test
    void theScreensData_isDerivedFromTheSameRoadmap() {
        RoadmapContract c = mockRoadmap().roadmapContract();
        var viz = c.visualizationSupport();

        assertThat(viz.timelineNodes()).extracting(n -> n.phaseType()).isEqualTo(c.phaseSequence());
        assertThat(viz.phaseCards()).isEqualTo(c.phases());
        assertThat(viz.milestoneCards()).isEqualTo(c.milestones());
        assertThat(viz.nextActionCard()).isEqualTo(c.nextBestAction());
        assertThat(viz.projectionChart().targetAmount()).isEqualByComparingTo("1500000000");
        assertThat(viz.projectionChart().projectedEndAmount()).isEqualByComparingTo("36428044.69");
        assertThat(viz.projectionChart().gapToTarget())
                .as("the gap of the mock")
                .isEqualByComparingTo("1463571955.31");
        assertThat(viz.projectionChart().trend()).isEqualTo("declining");
        assertThat(viz.statusBanner().tone()).isEqualTo("cautious");
        assertThat(viz.keyMetrics().currentProgressAmount()).isEqualByComparingTo("94380000");
        assertThat(viz.keyMetrics().currentProgressRatio()).isEqualByComparingTo("0.06292");
        assertThat(viz.keyMetrics().currentMonthlyCapacity().label()).isEqualTo("none");
        assertThat(viz.keyMetrics().timelineMonths()).isEqualTo(36);
        assertThat(viz.keyMetrics().feasibilityHint()).isEqualTo("low");
        assertThat(viz.unlockConditions()).extracting(u -> u.status()).containsExactly("current", "next");
    }

    @Test
    void theExplanations_areFilledAndMentionTheNumbers() {
        RoadmapResponse r = mockRoadmap();

        assertThat(r.uiExplanation().summary())
                .contains("Mua nhà")
                .contains("1.500.000.000 ₫")
                .contains("36 tháng");
        assertThat(r.uiExplanation().summary()).isEqualTo(r.result().summary());
        assertThat(r.uiExplanation().whyThisRoadmap())
                .contains("bảo vệ thanh khoản")
                .contains("→");
        assertThat(r.uiExplanation().whyCurrentPhase()).contains("94.380.000 ₫");
        assertThat(r.uiExplanation().whatHasToChangeNext())
                .startsWith("Khi ")
                .contains("giai đoạn \"" + PhaseType.STABILIZE.title() + "\"");
        assertThat(r.explanation().summary()).isEqualTo(r.uiExplanation().summary());
        var projection = r.explanation().projectionExplanation();
        assertThat(projection.basis()).contains("1.609.777 ₫");
        assertThat(projection.paceAssessment()).contains("âm");
        assertThat(projection.targetGapCommentary()).contains("36.428.045 ₫").contains("còn thiếu");
        assertThat(projection.trendCommentary()).contains("giảm");
        assertThat(r.explanation().cautions())
                .anyMatch(s -> s.contains("tham khảo"))
                .anyMatch(s -> s.contains("tiết kiệm âm"))
                .anyMatch(s -> s.contains("Cảnh báo chi bất thường"))
                .anyMatch(s -> s.contains("Khả năng đạt mục tiêu"));
        assertThat(r.explanation().confidenceNote()).contains("thận trọng");
    }

    @Test
    void aHealthyUser_startsAccumulating_andIsToldTheyAreOnPace() {
        RoadmapResponse r = RoadmapCompiler.compile(
                new RoadmapInput("Du lịch Nhật", d("60000000"), 12, d("24000000"), RoadmapFixtures.healthy(), false));
        RoadmapContract c = r.roadmapContract();

        assertThat(c.currentPhase()).isEqualTo("accumulate");
        assertThat(c.phaseSequence()).containsExactly("accumulate", "readiness_review", "maturity_transition");
        assertThat(c.goal().goalType()).isEqualTo("travel");
        // 24M + 9M x 12 = 132M >= 60M
        assertThat(c.projection().projectedEndAmount()).isEqualByComparingTo("132000000");
        assertThat(c.projection().confidenceLabel()).isEqualTo("directional");
        assertThat(c.maturityEvents().getFirst().eventType()).isEqualTo("goal_ready_for_execution");
        assertThat(c.visualizationSupport().statusBanner().tone()).isEqualTo("encouraging");
        assertThat(c.visualizationSupport().projectionChart().trend()).isEqualTo("improving");
        assertThat(r.explanation().projectionExplanation().paceAssessment()).startsWith("Tốc độ hiện tại đủ");
        assertThat(r.explanation().projectionExplanation().targetGapCommentary())
                .contains("đạt mục tiêu");
        assertThat(r.explanation().cautions()).hasSize(1);
        assertThat(r.explanation().confidenceNote()).contains("định hướng");
        assertThat(r.uiExplanation().whyThisRoadmap()).contains("đi thẳng vào tích lũy");
    }

    @Test
    void aReachedGoal_isAtTheMaturityTransition_withEarlierStagesCompleted() {
        RoadmapResponse r = RoadmapCompiler.compile(
                new RoadmapInput("Quỹ khẩn cấp", d("50000000"), 6, d("50000000"), RoadmapFixtures.healthy(), true));

        RoadmapContract c = r.roadmapContract();
        assertThat(c.currentPhase()).isEqualTo("maturity_transition");
        assertThat(c.phases()).extracting(PhaseData::status).containsExactly("completed", "completed", "current");
        assertThat(c.milestones()).extracting(m -> m.status()).containsExactly("completed", "completed", "current");
        assertThat(c.goal().goalType()).isEqualTo("emergency_fund");
        assertThat(r.uiExplanation().whatHasToChangeNext()).contains("chặng cuối");
        assertThat(c.visualizationSupport().unlockConditions()).hasSize(1);
    }

    // ------------------------------------------------------------------ too little is known

    @Test
    void aUserWithoutHistory_getsASetupRoadmap_likeTheLegacyPartialOne() {
        RoadmapResponse r = RoadmapCompiler.compile(
                new RoadmapInput("Mua xe", d("60000000"), 12, BigDecimal.ZERO, RoadmapFixtures.unknown(), false));
        RoadmapContract c = r.roadmapContract();

        // legacy test_run_service_returns_partial_setup_when_planner_state_missing
        assertThat(r.status()).isEqualTo("partial");
        assertThat(c.status()).isEqualTo("insufficient_financial_state");
        assertThat(c.missingFields()).isNotEmpty();
        assertThat(r.result().roadmapStatus()).isEqualTo("insufficient_financial_state");
        // legacy build_partial_roadmap: readiness review (current), then stabilize and accumulate
        assertThat(c.currentPhase()).isEqualTo("readiness_review");
        assertThat(c.phaseSequence()).containsExactly("readiness_review", "stabilize", "accumulate");
        assertThat(c.phases()).extracting(PhaseData::status).containsExactly("current", "upcoming", "upcoming");
        assertThat(c.phases().getFirst().title()).isEqualTo("Hoàn tất các thông tin còn thiếu");
        assertThat(c.journeyPattern()).isEmpty();
        assertThat(c.warnings()).hasSize(2);
        assertThat(c.nextBestAction().title()).contains("Ghi lại thu chi");
        assertThat(c.projection().confidenceLabel()).isEqualTo("provisional");
        assertThat(c.projection().series()).isEmpty();
        assertThat(c.goal().goalType()).isEqualTo("vehicle_purchase");
        assertThat(r.uiExplanation().summary()).contains("Hoàn tất các thông tin còn thiếu");
        assertThat(r.explanation().cautions()).anyMatch(s -> s.contains("Còn thiếu thông tin"));
        assertThat(r.explanation().confidenceNote()).contains("tạm thời");
        assertThat(c.visualizationSupport().projectionChart().gapToTarget()).isNull();
    }

    @Test
    void aGoalWithoutATimeline_isInsufficientGoalData() {
        RoadmapResponse r = RoadmapCompiler.compile(
                new RoadmapInput("x", BigDecimal.ZERO, 0, BigDecimal.ZERO, RoadmapFixtures.healthy(), false));

        assertThat(r.roadmapContract().status()).isEqualTo("insufficient_goal_data");
        assertThat(r.status()).isEqualTo("partial");
        assertThat(r.roadmapContract().currentPhase()).isEqualTo("readiness_review");
    }

    @Test
    void aPartialButUsableState_getsARealRoadmapWithWarnings() {
        // everything known except the feasibility: usable, but not "ready"
        var planner = RoadmapFixtures.state(
                "positive", "3000000", "moderate", "6.5", "low", "none", "ready", "unknown", "low", "0.1", "present");

        RoadmapResponse r =
                RoadmapCompiler.compile(new RoadmapInput("Quỹ", d("10000000"), 12, d("1000000"), planner, false));

        assertThat(r.status()).isEqualTo("partial");
        assertThat(r.roadmapContract().status()).isEqualTo("partial_but_usable");
        assertThat(r.roadmapContract().currentPhase()).isEqualTo("accumulate");
        assertThat(r.roadmapContract().missingFields()).containsExactly("planner_state.feasibility_hint");
        assertThat(r.roadmapContract().warnings()).isNotEmpty();
        assertThat(r.roadmapContract().projection().confidenceLabel()).isEqualTo("provisional");
        assertThat(r.roadmapContract().projection().series()).isNotEmpty();
    }

    // ------------------------------------------------------------------ the shape the UI reads

    /** The field names of {@code ui/src/types/roadmap.ts} (RoadmapResponse), camelCased, per object. */
    @Test
    void theJson_hasEveryFieldTheUiTypeDeclares_inCamelCase() {
        JsonNode root = JSON.valueToTree(mockRoadmap());

        assertHas(
                root,
                "schemaVersion",
                "agentId",
                "status",
                "result",
                "roadmapContract",
                "uiExplanation",
                "explanation");
        assertHas(root.get("result"), "summary", "roadmapStatus", "journeyPattern", "currentPhase", "phaseSequence");
        JsonNode contract = root.get("roadmapContract");
        assertHas(
                contract,
                "schemaVersion",
                "status",
                "goal",
                "plannerState",
                "journeyPattern",
                "currentPhase",
                "phaseSequence",
                "phases",
                "milestones",
                "projection",
                "nextBestAction",
                "visualizationSupport");
        assertHas(contract.get("goal"), "goalType", "targetAmount", "targetTimelineMonths", "priority");
        assertHas(
                contract.get("plannerState"),
                "cashflowStatus",
                "savingsCapacity",
                "runwayMonths",
                "liquidityPressure",
                "anomalyState",
                "readinessLabel",
                "feasibilityHint",
                "riskBand",
                "goalProgressRatio",
                "bufferStatus",
                "source");
        assertHas(contract.get("plannerState").get("savingsCapacity"), "amountMonthly", "label");
        assertHas(
                contract.get("phases").get(0),
                "phaseType",
                "title",
                "objective",
                "entryConditions",
                "exitConditions",
                "expectedResults",
                "serviceIds",
                "status");
        assertHas(
                contract.get("milestones").get(0),
                "milestoneId",
                "phaseType",
                "title",
                "unlockRule",
                "expectedResult",
                "status");
        assertHas(
                contract.get("projection"),
                "confidenceLabel",
                "basis",
                "targetAmount",
                "projectedEndAmount",
                "targetTimelineMonths",
                "series",
                "caution");
        assertHas(contract.get("projection").get("series").get(0), "month", "projectedAmount");
        assertHas(
                contract.get("nextBestAction"),
                "title",
                "why",
                "timeframe",
                "expectedOutcome",
                "successSignal",
                "followOnTransition");
        JsonNode viz = contract.get("visualizationSupport");
        assertHas(
                viz,
                "timelineNodes",
                "phaseCards",
                "milestoneCards",
                "projectionChart",
                "nextActionCard",
                "statusBanner",
                "keyMetrics",
                "unlockConditions");
        assertHas(viz.get("timelineNodes").get(0), "phaseType", "title", "status", "objective");
        assertHas(
                viz.get("projectionChart"),
                "confidenceLabel",
                "series",
                "targetAmount",
                "projectedEndAmount",
                "gapToTarget",
                "trend");
        assertHas(viz.get("statusBanner"), "tone", "title", "message");
        assertHas(
                viz.get("keyMetrics"),
                "currentMonthlyCapacity",
                "currentProgressAmount",
                "currentProgressRatio",
                "targetAmount",
                "timelineMonths",
                "projectedEndAmount",
                "feasibilityHint",
                "readinessLabel");
        assertHas(viz.get("keyMetrics").get("currentMonthlyCapacity"), "amount", "label");
        assertHas(viz.get("unlockConditions").get(0), "phaseType", "status", "conditions");
        assertHas(root.get("uiExplanation"), "summary", "whyThisRoadmap", "whyCurrentPhase", "whatHasToChangeNext");
        assertHas(root.get("explanation"), "summary", "projectionExplanation", "cautions", "confidenceNote");
        assertHas(
                root.get("explanation").get("projectionExplanation"),
                "basis",
                "paceAssessment",
                "targetGapCommentary",
                "trendCommentary");
    }

    @Test
    void theAmountsAreJsonNumbers_asTheUiTypesThemAsNumber() {
        JsonNode root = JSON.valueToTree(mockRoadmap());
        JsonNode contract = root.get("roadmapContract");

        assertThat(contract.get("goal").get("targetAmount").isNumber()).isTrue();
        assertThat(contract.get("goal").get("targetTimelineMonths").isInt()).isTrue();
        assertThat(contract.get("plannerState").get("runwayMonths").isNumber()).isTrue();
        assertThat(contract.get("plannerState").get("goalProgressRatio").isNumber())
                .isTrue();
        assertThat(contract.get("plannerState")
                        .get("savingsCapacity")
                        .get("amountMonthly")
                        .isNumber())
                .isTrue();
        assertThat(contract.get("projection").get("series").get(1).get("month").isInt())
                .isTrue();
        assertThat(contract.get("projection")
                        .get("series")
                        .get(1)
                        .get("projectedAmount")
                        .isNumber())
                .isTrue();
        assertThat(contract.get("visualizationSupport")
                        .get("keyMetrics")
                        .get("currentProgressAmount")
                        .isNumber())
                .isTrue();
    }

    @Test
    void everyStringInAReadyRoadmapIsFilledIn() {
        assertNoBlankStrings(JSON.valueToTree(mockRoadmap()), "$");
        assertNoBlankStrings(
                JSON.valueToTree(RoadmapCompiler.compile(
                        new RoadmapInput("Quỹ", d("100"), 3, d("0"), RoadmapFixtures.healthy(), false))),
                "$");
    }

    @Test
    void theSameInputGivesTheSameRoadmap() {
        assertThat(mockRoadmap()).isEqualTo(mockRoadmap());
    }

    private static void assertHas(JsonNode node, String... fields) {
        assertThat(node).isNotNull();
        for (String field : fields) {
            assertThat(node.has(field))
                    .as("field %s in %s", field, node.propertyNames().stream().toList())
                    .isTrue();
        }
    }

    private static void assertNoBlankStrings(JsonNode node, String path) {
        if (node.isString()) {
            assertThat(node.asString()).as(path).isNotBlank();
        } else if (node.isObject()) {
            node.properties().forEach(e -> assertNoBlankStrings(e.getValue(), path + "." + e.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                assertNoBlankStrings(node.get(i), path + "[" + i + "]");
            }
        }
    }
}
