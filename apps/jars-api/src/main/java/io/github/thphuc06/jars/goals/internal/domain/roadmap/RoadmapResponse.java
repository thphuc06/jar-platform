package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import java.math.BigDecimal;
import java.util.List;

/**
 * The staged plan of a goal, in the shape the screens of the legacy UI consume ({@code RoadmapResponse} in
 * {@code ui/src/types/roadmap.ts}): the same nesting and the same field names, in camelCase like the rest of this API
 * ({@code roadmap_contract} becomes {@code roadmapContract}). Amounts are plain JSON numbers here (the UI types them as
 * {@code number}, and its charts need numbers), not the string {@code Money} used elsewhere.
 *
 * <p>Fields the UI never reads but the legacy contract carried ({@code maturityEvents}, {@code missingFields},
 * {@code warnings}) are kept; the legacy LLM-only parts (service recommendations, per-service explanations) are not.
 */
public record RoadmapResponse(
        String schemaVersion,
        String agentId,
        String status,
        Result result,
        RoadmapContract roadmapContract,
        UiExplanation uiExplanation,
        Explanation explanation) {

    /** The headline of the roadmap. */
    public record Result(
            String summary,
            String roadmapStatus,
            String journeyPattern,
            String currentPhase,
            List<String> phaseSequence) {}

    /**
     * @param status {@code ready}, {@code partial_but_usable}, {@code insufficient_goal_data} or
     *     {@code insufficient_financial_state}; the UI only draws the roadmap for {@code ready}
     */
    public record RoadmapContract(
            String schemaVersion,
            String status,
            List<String> missingFields,
            List<String> warnings,
            RoadmapGoal goal,
            PlannerState plannerState,
            String journeyPattern,
            String currentPhase,
            List<String> phaseSequence,
            List<PhaseData> phases,
            List<MilestoneData> milestones,
            Projection projection,
            NextActionCard nextBestAction,
            List<MaturityEvent> maturityEvents,
            VisualizationSupport visualizationSupport) {}

    public record RoadmapGoal(String goalType, BigDecimal targetAmount, int targetTimelineMonths, String priority) {}

    /** What the user's money looks like now. Fields are null while there is not enough history to know them. */
    public record PlannerState(
            String cashflowStatus,
            SavingsCapacity savingsCapacity,
            BigDecimal runwayMonths,
            String liquidityPressure,
            String anomalyState,
            String readinessLabel,
            String feasibilityHint,
            String riskBand,
            BigDecimal goalProgressRatio,
            String bufferStatus,
            String source) {}

    public record SavingsCapacity(BigDecimal amountMonthly, String label) {}

    /** {@code status} is {@code completed}, {@code current} or {@code upcoming}. */
    public record PhaseData(
            String phaseType,
            String title,
            String objective,
            List<String> entryConditions,
            List<String> exitConditions,
            List<String> expectedResults,
            List<String> serviceIds,
            String status) {}

    public record MilestoneData(
            String milestoneId,
            String phaseType,
            String title,
            String unlockRule,
            String expectedResult,
            String status) {}

    public record ProjectionPoint(int month, BigDecimal projectedAmount) {}

    /** Where the current pace leads. {@code projectedEndAmount} and {@code series} are empty without a usable pace. */
    public record Projection(
            String confidenceLabel,
            String basis,
            BigDecimal targetAmount,
            BigDecimal projectedEndAmount,
            int targetTimelineMonths,
            List<ProjectionPoint> series,
            String caution) {}

    public record NextActionCard(
            String title,
            String why,
            String timeframe,
            String expectedOutcome,
            String successSignal,
            String followOnTransition) {}

    public record MaturityEvent(String eventType, String title, String condition) {}

    public record VisualizationSupport(
            List<TimelineNode> timelineNodes,
            List<PhaseData> phaseCards,
            List<MilestoneData> milestoneCards,
            ProjectionChart projectionChart,
            NextActionCard nextActionCard,
            StatusBanner statusBanner,
            KeyMetrics keyMetrics,
            List<UnlockCondition> unlockConditions) {}

    public record TimelineNode(String phaseType, String title, String status, String objective) {}

    /** {@code trend} is {@code improving}, {@code declining} or {@code flat}. */
    public record ProjectionChart(
            String confidenceLabel,
            List<ProjectionPoint> series,
            BigDecimal targetAmount,
            BigDecimal projectedEndAmount,
            BigDecimal gapToTarget,
            String trend) {}

    /** {@code tone} is {@code urgent}, {@code cautious}, {@code encouraging} or {@code supportive}. */
    public record StatusBanner(String tone, String title, String message) {}

    public record CurrentCapacity(BigDecimal amount, String label) {}

    public record KeyMetrics(
            CurrentCapacity currentMonthlyCapacity,
            BigDecimal currentProgressAmount,
            BigDecimal currentProgressRatio,
            BigDecimal targetAmount,
            int timelineMonths,
            BigDecimal projectedEndAmount,
            String feasibilityHint,
            String readinessLabel) {}

    /** {@code status} is {@code current} or {@code next}. */
    public record UnlockCondition(String phaseType, String status, List<String> conditions) {}

    public record UiExplanation(
            String summary, String whyThisRoadmap, String whyCurrentPhase, String whatHasToChangeNext) {}

    public record Explanation(
            String summary,
            ProjectionExplanation projectionExplanation,
            List<String> cautions,
            String confidenceNote) {}

    public record ProjectionExplanation(
            String basis, String paceAssessment, String targetGapCommentary, String trendCommentary) {}
}
