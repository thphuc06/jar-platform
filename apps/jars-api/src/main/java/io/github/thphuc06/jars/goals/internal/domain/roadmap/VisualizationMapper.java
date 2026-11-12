package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.CurrentCapacity;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.KeyMetrics;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.MilestoneData;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.NextActionCard;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PhaseData;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PlannerState;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.Projection;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.ProjectionChart;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.RoadmapGoal;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.StatusBanner;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.TimelineNode;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.UnlockCondition;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.VisualizationSupport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reshapes the roadmap into what the screens draw. Port of {@code visualization_mapper.py}. */
public final class VisualizationMapper {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal THREE_MONTHS = BigDecimal.valueOf(3);
    private static final BigDecimal SIX_MONTHS = BigDecimal.valueOf(6);

    private VisualizationMapper() {}

    public static VisualizationSupport build(
            List<PhaseData> phases,
            List<MilestoneData> milestones,
            Projection projection,
            NextActionCard nextAction,
            PlannerState planner,
            RoadmapGoal goal,
            String currentPhase,
            BigDecimal currentProgressAmount) {
        List<TimelineNode> nodes = phases.stream()
                .map(p -> new TimelineNode(p.phaseType(), p.title(), p.status(), p.objective()))
                .toList();
        BigDecimal gap = projection.projectedEndAmount() == null
                ? null
                : projection
                        .targetAmount()
                        .subtract(projection.projectedEndAmount())
                        .setScale(2, RoundingMode.HALF_EVEN);
        ProjectionChart chart = new ProjectionChart(
                projection.confidenceLabel(),
                projection.series(),
                projection.targetAmount(),
                projection.projectedEndAmount(),
                gap,
                trend(projection));
        KeyMetrics metrics = new KeyMetrics(
                new CurrentCapacity(
                        planner.savingsCapacity().amountMonthly(),
                        planner.savingsCapacity().label()),
                currentProgressAmount,
                planner.goalProgressRatio(),
                goal.targetAmount(),
                goal.targetTimelineMonths(),
                projection.projectedEndAmount(),
                planner.feasibilityHint(),
                planner.readinessLabel());
        return new VisualizationSupport(
                nodes,
                phases,
                milestones,
                chart,
                nextAction,
                statusBanner(planner, phases, currentPhase),
                metrics,
                unlockConditions(phases));
    }

    /** {@code improving} when the series ends more than 1 above where it starts, {@code declining} below, else {@code flat}. */
    static String trend(Projection projection) {
        if (projection.series().size() < 2) {
            return "flat";
        }
        BigDecimal first = projection.series().getFirst().projectedAmount();
        BigDecimal last = projection.series().getLast().projectedAmount();
        if (last.compareTo(first.add(ONE)) > 0) {
            return "improving";
        }
        return last.compareTo(first.subtract(ONE)) < 0 ? "declining" : "flat";
    }

    /**
     * The tone of the banner at the top of the roadmap, from the most to the least worrying:
     * {@code urgent} for tight liquidity or under 3 months of runway, {@code cautious} for an open anomaly, a negative
     * pace or a goal that is a stretch, {@code encouraging} while accumulating, otherwise {@code supportive}.
     */
    static StatusBanner statusBanner(PlannerState planner, List<PhaseData> phases, String currentPhase) {
        String phaseTitle = phases.stream()
                .filter(p -> "current".equals(p.status()))
                .map(PhaseData::title)
                .findFirst()
                .orElse(currentPhase)
                .toLowerCase();
        BigDecimal capacity = planner.savingsCapacity() == null
                ? null
                : planner.savingsCapacity().amountMonthly();
        boolean liquidityTight = Arrays.asList("critical", "high").contains(planner.liquidityPressure())
                || (planner.runwayMonths() != null && planner.runwayMonths().compareTo(THREE_MONTHS) < 0);
        boolean negativeCashflow =
                "negative".equals(planner.cashflowStatus()) || (capacity != null && capacity.signum() < 0);

        if (liquidityTight) {
            return new StatusBanner(
                    "urgent",
                    "Củng cố nền tảng trước khi dồn sức cho mục tiêu",
                    "Kế hoạch đang ở giai đoạn " + phaseTitle + " vì thanh khoản ngắn hạn còn mong manh. Hãy kiểm soát"
                            + " rủi ro tiền mặt trước để lộ trình không dựa vào một mức thu chi yếu.");
        }
        if ("active".equals(planner.anomalyState())
                || negativeCashflow
                || Arrays.asList("low", "stretch").contains(planner.feasibilityHint())) {
            boolean bufferPresent = "present".equals(planner.bufferStatus());
            boolean longRunway =
                    planner.runwayMonths() != null && planner.runwayMonths().compareTo(SIX_MONTHS) >= 0;
            String cushion = bufferPresent || longRunway
                    ? " Bạn vẫn còn đệm từ " + (bufferPresent ? "quỹ đang có" : "thời gian dự phòng")
                            + ", nên chưa phải là khẩn cấp về tiền mặt."
                    : "";
            return new StatusBanner(
                    "cautious",
                    "Bảo vệ dòng tiền trước khi dồn sức cho mục tiêu",
                    "Kế hoạch đang ở giai đoạn " + phaseTitle
                            + " vì tốc độ thu chi hiện tại chưa đủ mạnh để nuôi mục tiêu." + cushion);
        }
        if (PhaseType.ACCUMULATE.wire().equals(currentPhase)) {
            return new StatusBanner(
                    "encouraging",
                    "Kế hoạch sẵn sàng tạo đà",
                    "Mức thu chi hằng tháng đủ ổn định để góp thẳng vào mục tiêu, nên lộ trình tập trung vào các khoản"
                            + " góp đều đặn.");
        }
        return new StatusBanner(
                "supportive",
                "Lộ trình đang đi qua các bước có thứ tự",
                "Giai đoạn hiện tại được thiết kế để mở khóa giai đoạn sau một cách gọn gàng, không vượt quá nền tài"
                        + " chính hiện có.");
    }

    /** What ends the current stage, and what the next stage needs before it can start. */
    static List<UnlockCondition> unlockConditions(List<PhaseData> phases) {
        List<UnlockCondition> conditions = new ArrayList<>();
        int currentIndex = 0;
        for (int i = 0; i < phases.size(); i++) {
            if ("current".equals(phases.get(i).status())) {
                currentIndex = i;
                break;
            }
        }
        if (!phases.isEmpty()) {
            PhaseData current = phases.get(currentIndex);
            conditions.add(new UnlockCondition(current.phaseType(), "current", current.exitConditions()));
        }
        if (currentIndex + 1 < phases.size()) {
            PhaseData next = phases.get(currentIndex + 1);
            conditions.add(new UnlockCondition(next.phaseType(), "next", next.entryConditions()));
        }
        return List.copyOf(conditions);
    }
}
