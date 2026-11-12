package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.Explanation;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PhaseData;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PlannerState;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.Projection;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.ProjectionExplanation;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.UiExplanation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The sentences that explain a roadmap. In the legacy backend a language model wrote these ({@code explanation_layer.py}
 * with Bedrock); here they are fixed templates filled from the numbers, so the same state always reads the same and no
 * model is involved. The set of fields is the one the UI reads ({@code ui_explanation}, {@code explanation}).
 */
final class ExplanationWriter {

    private static final BigDecimal THREE_MONTHS = BigDecimal.valueOf(3);

    private ExplanationWriter() {}

    /**
     * @param phases the stages as shown to the user, in order (their titles may differ from the catalog's in a setup
     *     roadmap)
     * @param sequence the stage types in the same order
     * @param current the stage the user is in
     * @param projectedEnd where the pace leads at the end of the timeline, or null
     * @param trend {@code improving}, {@code declining} or {@code flat}
     * @param status the readiness class of the roadmap
     * @param missingFields what was missing for a complete roadmap
     */
    record Context(
            String goalName,
            BigDecimal target,
            int timelineMonths,
            BigDecimal currentAmount,
            PlannerState planner,
            Projection projection,
            List<PhaseData> phases,
            List<PhaseType> sequence,
            PhaseType current,
            String trend,
            String status,
            List<String> missingFields) {}

    static UiExplanation ui(Context c) {
        return new UiExplanation(summary(c), whyThisRoadmap(c), whyCurrentPhase(c), whatHasToChangeNext(c));
    }

    static Explanation explanation(Context c) {
        return new Explanation(summary(c), projectionExplanation(c), cautions(c), confidenceNote(c));
    }

    static String summary(Context c) {
        String reason = switch (c.current()) {
            case PROTECT_LIQUIDITY ->
                "active".equals(c.planner().anomalyState())
                        ? " Có cảnh báo chi bất thường chưa xử lý nên cần bảo vệ dòng tiền trước."
                        : " Quỹ dự phòng còn mỏng nên cần bảo vệ thanh khoản trước.";
            case STABILIZE -> " Dòng tiền hằng tháng chưa dương nên cần ổn định thu chi trước khi góp cho mục tiêu.";
            case ACCUMULATE -> " Dòng tiền đủ ổn định để bắt đầu góp đều cho mục tiêu.";
            case READINESS_REVIEW ->
                "ready".equals(c.status())
                        ? " Cần rà soát tốc độ góp so với thời hạn."
                        : " Cần bổ sung thông tin còn thiếu trước khi lập lộ trình chắc chắn.";
            case MATURITY_TRANSITION -> " Mục tiêu đã đạt hoặc sắp đến lúc ra quyết định.";
        };
        return "Mục tiêu \"" + c.goalName() + "\": " + Vnd.format(c.target()) + " trong " + c.timelineMonths()
                + " tháng. Lộ trình đang ở giai đoạn \"" + currentPhase(c).title() + "\"." + reason;
    }

    static String whyThisRoadmap(Context c) {
        String stages = c.phases().stream().map(PhaseData::title).collect(Collectors.joining(" → "));
        String opening;
        if (c.sequence().contains(PhaseType.PROTECT_LIQUIDITY)) {
            opening = "Lộ trình mở đầu bằng bảo vệ thanh khoản vì có cảnh báo chi bất thường hoặc quỹ dự phòng chưa đủ"
                    + " dày, khiến việc tiết kiệm lúc này kém tin cậy.";
        } else if (c.sequence().contains(PhaseType.STABILIZE)) {
            opening = "Lộ trình mở đầu bằng ổn định dòng tiền vì thu chi hằng tháng chưa để lại phần dư để góp cho mục"
                    + " tiêu.";
        } else {
            opening = "Dòng tiền hiện đủ vững nên lộ trình đi thẳng vào tích lũy.";
        }
        return opening + " Các giai đoạn: " + stages + ". Thứ tự này tránh xây kế hoạch tiết kiệm trên một nền thu chi"
                + " chưa vững.";
    }

    static String whyCurrentPhase(Context c) {
        return currentPhase(c).objective() + " Mục tiêu đang có " + Vnd.format(c.currentAmount()) + " trên "
                + Vnd.format(c.target()) + ".";
    }

    static String whatHasToChangeNext(Context c) {
        int next = c.sequence().indexOf(c.current()) + 1;
        if (next >= c.sequence().size()) {
            return "Đây là chặng cuối của lộ trình: bước tiếp theo là thực hiện mục tiêu hoặc đặt một kế hoạch mới.";
        }
        PhaseData after = c.phases().get(next);
        return "Khi "
                + lowerFirst(withoutFinalDot(currentPhase(c).exitConditions().getFirst()))
                + ", lộ trình sẽ chuyển sang giai đoạn \"" + after.title() + "\".";
    }

    static ProjectionExplanation projectionExplanation(Context c) {
        BigDecimal capacity = c.planner().savingsCapacity() == null
                ? null
                : c.planner().savingsCapacity().amountMonthly();
        String basis = capacity == null
                ? "Chưa đủ lịch sử thu chi để tính mức tiết kiệm hằng tháng."
                : "Dự phóng dựa trên mức tiết kiệm hằng tháng trung bình " + Vnd.format(capacity)
                        + " (thu trừ chi của các tháng gần đây).";

        BigDecimal required = c.target()
                .subtract(c.currentAmount())
                .max(BigDecimal.ZERO)
                .divide(BigDecimal.valueOf(Math.max(1, c.timelineMonths())), 2, RoundingMode.CEILING);
        String pace;
        if (capacity == null) {
            pace = "Chưa đánh giá được tốc độ góp vì thiếu lịch sử thu chi.";
        } else if (capacity.compareTo(required) >= 0) {
            pace = "Tốc độ hiện tại đủ: cần " + Vnd.format(required) + "/tháng, đang có " + Vnd.format(capacity)
                    + "/tháng.";
        } else if (capacity.signum() > 0) {
            pace = "Tốc độ hiện tại chưa đủ: cần " + Vnd.format(required) + "/tháng nhưng mới có "
                    + Vnd.format(capacity) + "/tháng.";
        } else {
            pace = "Tốc độ hiện tại âm: số dư đang giảm thay vì tăng qua các tháng.";
        }

        String gap = "";
        if (c.projection().projectedEndAmount() != null) {
            BigDecimal end = c.projection().projectedEndAmount();
            BigDecimal missing = c.target().subtract(end);
            gap = missing.signum() <= 0
                    ? "Dự phóng cuối kỳ là " + Vnd.format(end) + ", đạt mục tiêu."
                    : "Dự phóng cuối kỳ là " + Vnd.format(end) + ", còn thiếu " + Vnd.format(missing)
                            + " so với mục tiêu.";
        }
        String trend = switch (c.trend()) {
            case "improving" -> "Chuỗi dự phóng tăng từ tháng 0 đến tháng " + c.timelineMonths() + ".";
            case "declining" ->
                "Chuỗi dự phóng giảm từ tháng 0 đến tháng " + c.timelineMonths()
                        + ", phản ánh mức tiết kiệm âm cho tới khi dòng tiền được sửa.";
            default -> "Chuỗi dự phóng đi ngang trong suốt " + c.timelineMonths() + " tháng.";
        };
        return new ProjectionExplanation(basis, pace, gap, trend);
    }

    static List<String> cautions(Context c) {
        List<String> cautions = new ArrayList<>();
        cautions.add("Các con số chỉ mang tính tham khảo để lập kế hoạch, không phải cam kết kết quả.");
        BigDecimal capacity = c.planner().savingsCapacity() == null
                ? null
                : c.planner().savingsCapacity().amountMonthly();
        if (capacity != null && capacity.signum() < 0) {
            cautions.add("Mức tiết kiệm âm nghĩa là số dư giảm mỗi tháng cho tới khi dòng tiền được sửa.");
        }
        if ("active".equals(c.planner().anomalyState())) {
            cautions.add("Cảnh báo chi bất thường cần được xử lý trước khi lập nhịp góp đều.");
        }
        if (Arrays.asList("low", "stretch").contains(c.planner().feasibilityHint())) {
            cautions.add("Khả năng đạt mục tiêu đang thấp: cần tăng tốc độ góp hoặc kéo dài thời hạn.");
        }
        BigDecimal runway = c.planner().runwayMonths();
        if (runway != null && runway.compareTo(THREE_MONTHS) < 0) {
            cautions.add("Tiền trong các hũ chỉ đủ cho "
                    + runway.stripTrailingZeros().toPlainString() + " tháng chi tiêu.");
        }
        if (!ReadinessClassifier.READY.equals(c.status()) && !c.missingFields().isEmpty()) {
            cautions.add("Còn thiếu thông tin nên lộ trình mới ở mức tạm thời ("
                    + c.missingFields().size() + " mục).");
        }
        return List.copyOf(cautions);
    }

    static String confidenceNote(Context c) {
        return switch (c.projection().confidenceLabel()) {
            case "provisional" ->
                "Độ tin cậy tạm thời: còn thiếu thông tin nên lộ trình có thể thay đổi khi có thêm dữ liệu thu chi.";
            case "directional" ->
                "Độ tin cậy mang tính định hướng: dự phóng dựa trên mức thu chi trung bình của các tháng gần đây.";
            default -> "Độ tin cậy thận trọng: mức độ khả thi hiện còn thấp, nên cần xem lại dự phóng thường xuyên.";
        };
    }

    private static PhaseData currentPhase(Context c) {
        return c.phases().get(c.sequence().indexOf(c.current()));
    }

    private static String withoutFinalDot(String text) {
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    private static String lowerFirst(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
