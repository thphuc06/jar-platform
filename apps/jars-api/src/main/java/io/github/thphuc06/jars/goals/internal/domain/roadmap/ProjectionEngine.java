package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.MaturityEvent;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.Projection;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.ProjectionPoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Where the current monthly pace leads, at four checkpoints of the timeline, and what that means for the end of the
 * roadmap. Ports of {@code projection_engine.py} and {@code maturity_engine.py}.
 */
public final class ProjectionEngine {

    public static final String BASIS = "planner_state_monthly_capacity";
    private static final String CAUTION =
            "Các con số chỉ mang tính tham khảo để lập kế hoạch, không phải cam kết kết quả.";
    private static final String MISSING_CAUTION =
            "Cần có mục tiêu hợp lệ và mức tiết kiệm hằng tháng đã tính được thì mới dự phóng có cơ sở.";

    private ProjectionEngine() {}

    /**
     * The amount at month 0, t/3, 2t/3 and t ({@code t} = timeline in months; checkpoints that coincide are listed once):
     * {@code max(0, start + capacity * month)}. A negative capacity gives a declining series that stops at 0.
     *
     * @param startingAmount what is saved now
     * @param capacityMonthly the monthly net, or null when it is not known: then the projection is empty and
     *     "provisional"
     * @param readinessClass the class from {@link ReadinessClassifier}; anything but {@code ready} is "provisional"
     * @param feasibilityHint {@code high} or {@code medium} make the projection "directional", else it is "cautious"
     */
    public static Projection build(
            BigDecimal target,
            int timelineMonths,
            BigDecimal capacityMonthly,
            BigDecimal startingAmount,
            String readinessClass,
            String feasibilityHint) {
        if (target == null || target.signum() <= 0 || timelineMonths <= 0 || capacityMonthly == null) {
            return new Projection(
                    "provisional",
                    "missing_target_or_capacity",
                    target,
                    null,
                    timelineMonths,
                    List.of(),
                    MISSING_CAUTION);
        }
        // a TreeSet: sorted, and checkpoints that coincide (a very short timeline) are listed once
        Set<Integer> checkpoints = new TreeSet<>(
                List.of(0, Math.max(1, timelineMonths / 3), Math.max(1, (2 * timelineMonths) / 3), timelineMonths));
        List<ProjectionPoint> series = checkpoints.stream()
                .map(month -> new ProjectionPoint(month, amountAt(startingAmount, capacityMonthly, month)))
                .toList();
        BigDecimal end = amountAt(startingAmount, capacityMonthly, timelineMonths);

        String confidence;
        if (!ReadinessClassifier.READY.equals(readinessClass)) {
            confidence = "provisional";
        } else if ("high".equals(feasibilityHint) || "medium".equals(feasibilityHint)) {
            confidence = "directional";
        } else {
            confidence = "cautious";
        }
        return new Projection(confidence, BASIS, target, end, timelineMonths, series, CAUTION);
    }

    static BigDecimal amountAt(BigDecimal start, BigDecimal capacity, int month) {
        return start.add(capacity.multiply(BigDecimal.valueOf(month)))
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Legacy {@code build_maturity_events}: when the projection reaches the target, the goal looks reachable; otherwise a
     * readiness review is needed before committing.
     */
    public static List<MaturityEvent> maturityEvents(BigDecimal target, BigDecimal projectedEnd) {
        BigDecimal end = projectedEnd == null ? BigDecimal.ZERO : projectedEnd;
        if (target != null && target.signum() > 0 && end.compareTo(target) >= 0) {
            return List.of(new MaturityEvent(
                    "goal_ready_for_execution",
                    "Mục tiêu có vẻ đạt được theo kế hoạch hiện tại",
                    "các khoản góp dự kiến chạm mục tiêu trong thời hạn hiện tại"));
        }
        return List.of(new MaturityEvent(
                "readiness_review_required",
                "Cần rà soát mức sẵn sàng trước khi cam kết cuối cùng",
                "tốc độ góp hiện tại vẫn thấp hơn đường đi tới mục tiêu"));
    }
}
