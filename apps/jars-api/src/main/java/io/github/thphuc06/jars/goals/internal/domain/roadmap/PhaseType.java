package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.NextActionCard;
import java.util.List;
import java.util.Optional;

/**
 * The stages a roadmap moves through, with their texts. Ported from {@code service_agent/constants.py},
 * {@code catalog.py}, {@code milestone_engine.py} and {@code roadmap_compiler.py} of the legacy backend, translated to
 * Vietnamese (the legacy texts were English and written by a model; these are fixed texts).
 *
 * <p>{@link #wire()} is the identifier the UI uses ({@code phase_type}).
 */
public enum PhaseType {
    PROTECT_LIQUIDITY(
            "protect_liquidity",
            "protect",
            "Bảo vệ thanh khoản trước",
            "Chặn các khoản chi bất thường và rò rỉ tiền trước khi kế hoạch mất thêm vốn.",
            List.of(
                    "Có cảnh báo chi bất thường hoặc quỹ dự phòng đang mỏng.",
                    "Mức thu chi hằng tháng hiện chưa đủ tin cậy để tiết kiệm mạnh tay."),
            List.of(
                    "Các cảnh báo đã được xem xét và những khoản rò rỉ được chặn lại.",
                    "Số dư ngắn hạn không còn bị rút dần bởi những khoản chi chưa rõ nguyên nhân."),
            List.of(
                    "Số dư ngắn hạn an toàn hơn và dòng tiền ra dễ tin cậy hơn.",
                    "Lộ trình chuyển sang sửa dòng tiền với ít khoản mất tiền không rõ hơn."),
            List.of("anomaly_review", "liquidity_guardrails", "emergency_buffer_setup"),
            "Xử lý xong cảnh báo chi bất thường",
            "Các giao dịch bị gắn cờ đã được xem xét và biện pháp bảo vệ đã bật",
            "Tiền không còn rò rỉ qua những khoản chi có thể tránh được hoặc đáng ngờ",
            new NextActionCard(
                    "Rà soát các giao dịch rủi ro nhất và siết lại giới hạn thanh khoản",
                    "Khi có cảnh báo hoặc thanh khoản yếu thì bảo vệ dòng tiền phải đi trước.",
                    "trong 24 giờ",
                    "Những khoản chi đáng ngờ hoặc tránh được được giải thích, chặn lại hoặc khoanh vùng để bức tranh dòng tiền dùng được trở lại.",
                    "Các giao dịch bị gắn cờ đã được xem xét và bạn nói được tiền đang đi đâu.",
                    "Khi nguy cơ rò rỉ được kiểm soát, lộ trình chuyển sang giai đoạn ổn định và sửa mức thu chi hằng tháng.")),
    STABILIZE(
            "stabilize",
            "stabilize",
            "Ổn định dòng tiền hằng tháng",
            "Đưa thu chi hằng tháng về mức kiểm soát được để có thể nuôi mục tiêu.",
            List.of(
                    "Chi tiêu thiết yếu cần dễ đoán hơn trước khi dồn sức cho mục tiêu.",
                    "Kế hoạch vẫn cần một biên độ thu chi hằng tháng ổn định hơn."),
            List.of("Dòng tiền hằng tháng không còn âm kéo dài.", "Chi tiêu thiết yếu nằm trong mức kiểm soát được."),
            List.of(
                    "Kế hoạch không còn phải dựa vào việc lặng lẽ rút số dư để qua những tháng bình thường.",
                    "Lộ trình chuyển từ sửa chữa sang tiến bộ đều đặn."),
            List.of("budget_controls", "recurring_expense_cleanup", "liquidity_guardrails"),
            "Đưa dòng tiền hằng tháng về mức kiểm soát được",
            "Chi tiêu thiết yếu nằm trong mức kiểm soát được",
            "Dòng tiền hằng tháng ổn định hơn và dễ lập kế hoạch hơn",
            new NextActionCard(
                    "Cắt giảm hoặc khoanh vùng những khoản chi dễ kiểm soát nhất",
                    "Mức thu chi hằng tháng ổn định giúp phần còn lại của lộ trình thực tế hơn nhiều.",
                    "trong tuần này",
                    "Thu chi hằng tháng dễ quản lý hơn và bớt phụ thuộc vào số dư có sẵn.",
                    "Chi tiêu thiết yếu giảm đủ để dòng tiền hằng tháng không còn âm kéo dài.",
                    "Khi thu chi hằng tháng ổn định, lộ trình chuyển sang giai đoạn tích lũy và bắt đầu góp thẳng vào mục tiêu.")),
    ACCUMULATE(
            "accumulate",
            "accumulate",
            "Đều đặn góp vào mục tiêu",
            "Biến mức thu chi đã ổn định thành các khoản góp đều đặn cho mục tiêu.",
            List.of(
                    "Kế hoạch đã đủ ổn định để bắt đầu góp trực tiếp cho mục tiêu.",
                    "Có thể góp một khoản đều mỗi tháng mà không làm căng lại thanh khoản."),
            List.of(
                    "Các khoản góp đã chạy và bám theo tốc độ cần có.",
                    "Một hũ riêng cho mục tiêu đang được dùng và khoản góp hằng tháng lặp lại."),
            List.of(
                    "Tiến độ nhìn thấy được và dễ duy trì hơn.",
                    "Bạn đánh giá được tốc độ thực so với thời hạn của mục tiêu."),
            List.of("goal_bucket_setup", "auto_save_activation", "contribution_review"),
            "Bắt đầu nhịp góp đều hằng tháng",
            "Hũ cho mục tiêu và dòng tiền góp đều đã hoạt động",
            "Tiền cho mục tiêu bắt đầu tăng với tốc độ đều",
            new NextActionCard(
                    "Bật một khoản chuyển định kỳ đơn giản vào hũ của mục tiêu",
                    "Khi đã sẵn sàng tích lũy thì đều đặn quan trọng hơn dồn dập.",
                    "trong tuần này",
                    "Mục tiêu bắt đầu nhận những khoản góp đều từ phần thặng dư thật của mỗi tháng.",
                    "Khoản chuyển định kỳ đã chạy và thực sự lặp lại tháng này qua tháng khác.",
                    "Khi nhịp góp đã ổn định, lộ trình vào giai đoạn rà soát để kiểm tra tốc độ có đủ không.")),
    READINESS_REVIEW(
            "readiness_review",
            "review",
            "Rà soát mức sẵn sàng trước chặng cuối",
            "Kiểm tra lại tốc độ và giả định hiện tại còn khớp với thời hạn của mục tiêu không.",
            List.of(
                    "Lộ trình đủ ổn định để kiểm tra lại tốc độ trước chặng cuối.",
                    "Đã có nhịp góp đều để so với tốc độ cần có."),
            List.of(
                    "Kế hoạch được hiệu chỉnh theo tốc độ và thời hạn hiện tại.",
                    "Có quyết định rõ: giữ nguyên lộ trình hay đặt lại."),
            List.of(
                    "Bước chuyển tiếp sau diễn ra ít bất ngờ hơn.",
                    "Bạn có cái nhìn thực tế hơn về mức sẵn sàng thực hiện mục tiêu."),
            List.of("readiness_check", "contribution_review"),
            "Kiểm tra tốc độ hiện tại còn đủ không",
            "Kế hoạch đủ ổn định để rà soát có cấu trúc",
            "Có thể điều chỉnh lộ trình trước chặng cuối",
            new NextActionCard(
                    "So tốc độ góp hiện tại với thời hạn của mục tiêu",
                    "Một lần rà soát ngắn bây giờ giúp tránh lệch hướng về sau.",
                    "trong tháng này",
                    "Bạn có câu trả lời rõ: tốc độ hiện tại có kịp thời hạn không.",
                    "Lộ trình có quyết định rõ: giữ nguyên, tăng tốc hay đặt lại đường đi.",
                    "Rà soát này mở đường cho bước chuyển sang thực hiện mục tiêu hoặc đặt lại kế hoạch có chủ ý.")),
    MATURITY_TRANSITION(
            "maturity_transition",
            "maturity",
            "Xử lý khi mục tiêu đến hạn và các lựa chọn tiếp theo",
            "Xử lý bước chuyển khi mục tiêu sắp thực hiện hoặc cần một điểm quyết định cuối.",
            List.of("Mục tiêu sắp đến hạn hoặc kế hoạch đã tới điểm quyết định có/không."),
            List.of(
                    "Sẵn sàng bắt đầu thực hiện mục tiêu hoặc xử lý sau mục tiêu.",
                    "Lộ trình đã tới một điểm quyết định rõ thay vì trôi dần."),
            List.of("Bạn chuyển sang trạng thái tài chính tiếp theo một cách gọn gàng."),
            List.of(),
            "Chuẩn bị chuyển giao khi mục tiêu hoàn thành",
            "Mục tiêu gần hoàn thành hoặc đã qua cổng sẵn sàng",
            "Bạn có thể chuyển sang thực hiện hoặc xử lý sau mục tiêu",
            new NextActionCard(
                    "Xem các lựa chọn khi mục tiêu đến hạn",
                    "Lộ trình đã đủ gần để chuyển từ chuẩn bị sang ra quyết định.",
                    "kỳ rà soát tới",
                    "Các lựa chọn thực hiện trở nên rõ ràng và lộ trình kết thúc bằng một bước chuyển có chủ ý.",
                    "Bạn đi tới quyết định thực hiện hoặc chưa thực hiện thay vì trì hoãn vô thời hạn.",
                    "Lộ trình khép lại bằng việc thực hiện, xử lý sau mục tiêu hoặc bắt đầu một chu kỳ lập kế hoạch mới."));

    private final String wire;
    private final String shortName;
    private final String title;
    private final String objective;
    private final List<String> entryConditions;
    private final List<String> exitConditions;
    private final List<String> expectedResults;
    private final List<String> serviceIds;
    private final String milestoneTitle;
    private final String unlockRule;
    private final String milestoneResult;
    private final NextActionCard nextAction;

    PhaseType(
            String wire,
            String shortName,
            String title,
            String objective,
            List<String> entryConditions,
            List<String> exitConditions,
            List<String> expectedResults,
            List<String> serviceIds,
            String milestoneTitle,
            String unlockRule,
            String milestoneResult,
            NextActionCard nextAction) {
        this.wire = wire;
        this.shortName = shortName;
        this.title = title;
        this.objective = objective;
        this.entryConditions = entryConditions;
        this.exitConditions = exitConditions;
        this.expectedResults = expectedResults;
        this.serviceIds = serviceIds;
        this.milestoneTitle = milestoneTitle;
        this.unlockRule = unlockRule;
        this.milestoneResult = milestoneResult;
        this.nextAction = nextAction;
    }

    /** The identifier in the JSON, for example {@code protect_liquidity}. */
    public String wire() {
        return wire;
    }

    /** The word used in a journey pattern name such as {@code protect_then_accumulate_then_review}. */
    public String shortName() {
        return shortName;
    }

    public String title() {
        return title;
    }

    public String objective() {
        return objective;
    }

    public List<String> entryConditions() {
        return entryConditions;
    }

    public List<String> exitConditions() {
        return exitConditions;
    }

    public List<String> expectedResults() {
        return expectedResults;
    }

    /** Identifiers of the services that help in this stage (the legacy catalog's defaults). */
    public List<String> serviceIds() {
        return serviceIds;
    }

    public String milestoneTitle() {
        return milestoneTitle;
    }

    public String unlockRule() {
        return unlockRule;
    }

    public String milestoneResult() {
        return milestoneResult;
    }

    public NextActionCard nextAction() {
        return nextAction;
    }

    public static Optional<PhaseType> fromWire(String wire) {
        for (PhaseType type : values()) {
            if (type.wire.equals(wire)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
