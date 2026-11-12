package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.ExplanationWriter.Context;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.JourneyPlanner.Journey;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.ReadinessClassifier.Classification;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.MaturityEvent;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.MilestoneData;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.NextActionCard;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PhaseData;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PlannerState;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.Projection;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.Result;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.RoadmapContract;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.RoadmapGoal;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.VisualizationSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts the pieces together: classifies how much is known, chooses the stages, and builds the milestones, projection,
 * next action, maturity events, screens data and explanations. A port of {@code roadmap_compiler.py} (and
 * {@code build_partial_roadmap} for the case where too little is known).
 *
 * <p>Everything is a pure function of the input: no clock, no randomness, no database.
 */
public final class RoadmapCompiler {

    public static final String SCHEMA_VERSION = "v1";
    public static final String CONTRACT_VERSION = "roadmap_contract_v1";
    public static final String AGENT_ID = "goals";
    public static final String PRIORITY = "medium";

    private RoadmapCompiler() {}

    /**
     * @param timelineMonths months from today to the target date, at least 1
     * @param currentAmount what the funding jar holds (the starting point of the projection)
     * @param goalReached the funding jar already holds the target
     */
    public record RoadmapInput(
            String goalName,
            BigDecimal targetAmount,
            int timelineMonths,
            BigDecimal currentAmount,
            PlannerState planner,
            boolean goalReached) {}

    public static RoadmapResponse compile(RoadmapInput in) {
        Classification classification =
                ReadinessClassifier.classify(in.targetAmount(), in.timelineMonths(), in.planner());
        boolean setupOnly = classification.needsSetupRoadmap();

        Journey journey = setupOnly ? setupJourney() : JourneyPlanner.plan(in.planner(), in.goalReached());
        List<PhaseData> phases = new ArrayList<>();
        for (PhaseType type : journey.sequence()) {
            phases.add(
                    setupOnly && type == PhaseType.READINESS_REVIEW
                            ? setupPhase()
                            : phase(type, JourneyPlanner.statusOf(type, journey.sequence(), journey.current())));
        }
        List<MilestoneData> milestones = MilestoneEngine.build(journey.sequence(), journey.current());

        BigDecimal capacity = in.planner().savingsCapacity() == null
                ? null
                : in.planner().savingsCapacity().amountMonthly();
        Projection projection = ProjectionEngine.build(
                in.targetAmount(),
                in.timelineMonths(),
                capacity,
                in.currentAmount(),
                classification.status(),
                in.planner().feasibilityHint());
        List<MaturityEvent> maturityEvents =
                ProjectionEngine.maturityEvents(in.targetAmount(), projection.projectedEndAmount());
        NextActionCard nextAction =
                setupOnly ? setupNextAction() : journey.current().nextAction();

        RoadmapGoal goal =
                new RoadmapGoal(GoalTypes.classify(in.goalName()), in.targetAmount(), in.timelineMonths(), PRIORITY);
        VisualizationSupport visualization = VisualizationMapper.build(
                phases,
                milestones,
                projection,
                nextAction,
                in.planner(),
                goal,
                journey.current().wire(),
                in.currentAmount());

        List<String> warnings = classification.status().equals(ReadinessClassifier.READY)
                ? List.of()
                : List.of(
                        "Lộ trình này còn tạm thời vì thiếu một số thông tin về mục tiêu hoặc tình hình tài chính.",
                        "Hãy ghi lại thu chi trong vài tuần để lộ trình dựa trên số liệu thật.");
        List<String> sequenceWire =
                journey.sequence().stream().map(PhaseType::wire).toList();
        Context context = new Context(
                in.goalName(),
                in.targetAmount(),
                in.timelineMonths(),
                in.currentAmount(),
                in.planner(),
                projection,
                List.copyOf(phases),
                journey.sequence(),
                journey.current(),
                visualization.projectionChart().trend(),
                classification.status(),
                classification.missingFields());

        RoadmapContract contract = new RoadmapContract(
                CONTRACT_VERSION,
                classification.status(),
                classification.missingFields(),
                warnings,
                goal,
                in.planner(),
                journey.pattern(),
                journey.current().wire(),
                sequenceWire,
                List.copyOf(phases),
                milestones,
                projection,
                nextAction,
                maturityEvents,
                visualization);
        return new RoadmapResponse(
                SCHEMA_VERSION,
                AGENT_ID,
                ReadinessClassifier.READY.equals(classification.status()) ? "ok" : "partial",
                new Result(
                        ExplanationWriter.summary(context),
                        classification.status(),
                        journey.pattern(),
                        journey.current().wire(),
                        sequenceWire),
                contract,
                ExplanationWriter.ui(context),
                ExplanationWriter.explanation(context));
    }

    private static PhaseData phase(PhaseType type, String status) {
        return new PhaseData(
                type.wire(),
                type.title(),
                type.objective(),
                type.entryConditions(),
                type.exitConditions(),
                type.expectedResults(),
                type.serviceIds(),
                status);
    }

    // ---- too little is known: the legacy build_partial_roadmap (review the setup, then stabilize, then accumulate)

    private static Journey setupJourney() {
        return new Journey(
                List.of(PhaseType.READINESS_REVIEW, PhaseType.STABILIZE, PhaseType.ACCUMULATE),
                "",
                PhaseType.READINESS_REVIEW);
    }

    private static PhaseData setupPhase() {
        return new PhaseData(
                PhaseType.READINESS_REVIEW.wire(),
                "Hoàn tất các thông tin còn thiếu",
                "Thu thập các thông tin về mục tiêu hoặc tình hình tài chính còn thiếu trước khi quyết định lộ trình.",
                List.of("Hiện còn thiếu một số thông tin quan trọng về mục tiêu hoặc về thu chi."),
                List.of("Đã có đủ các thông tin còn thiếu về mục tiêu và thu chi."),
                List.of("Lần lập lộ trình sau sẽ có cơ sở vững hơn."),
                List.of(),
                "current");
    }

    private static NextActionCard setupNextAction() {
        return new NextActionCard(
                "Ghi lại thu chi để có đủ dữ kiện lập lộ trình",
                "Lộ trình chắc chắn cần cả một mục tiêu rõ ràng và lịch sử thu chi của bạn.",
                "ngay bây giờ",
                "Lần lập lộ trình tiếp theo có đủ dữ kiện để đi vào giai đoạn cụ thể.",
                "Các tháng gần đây đã có thu nhập và chi tiêu được ghi lại.",
                "Khi đã đủ dữ kiện, lộ trình chuyển từ bước chuẩn bị sang giai đoạn đầu tiên thật sự.");
    }
}
