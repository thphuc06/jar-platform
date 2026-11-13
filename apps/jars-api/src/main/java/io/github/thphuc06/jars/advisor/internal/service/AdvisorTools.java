package io.github.thphuc06.jars.advisor.internal.service;

import io.github.thphuc06.jars.goals.GoalsApi;
import io.github.thphuc06.jars.insights.InsightsApi;
import io.github.thphuc06.jars.insights.OpenAnomaly;
import io.github.thphuc06.jars.insights.SpendingBehaviour;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.util.List;
import java.util.function.BiConsumer;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * What the model may look up: read-only views of the user's own data, computed by the other modules. The user is never
 * a tool parameter: it comes from the JWT through the {@link ToolContext} the service fills, so a prompt cannot make a
 * tool read someone else's data. Amounts are plain VND strings; the model is told to quote them, not to compute new
 * ones (ADR-0011: "Java tính, LLM diễn giải").
 */
@Component
public class AdvisorTools {

    static final String USER = "userId";
    /** Receives (tool name, "started"/"done") for the SSE {@code tool} events and the JSON results for the number check. */
    static final String EVENTS = "events";

    public record JarInfo(String code, String name, String balanceVnd, String targetPercent) {}

    public record MonthInfo(String month, String incomeVnd, String expenseVnd, String netVnd) {}

    public record GoalInfo(String name, String targetVnd, String targetDate, String status) {}

    public record AnomalyInfo(
            String jar,
            String type,
            String severity,
            String amountVnd,
            String usualVnd,
            String detectedOn,
            String explanation) {}

    public record OpenAnomaliesInfo(long totalOpen, List<AnomalyInfo> anomalies) {}

    public record CategoryInfo(
            String category, String spentVnd, String usualVnd, String differenceVnd, Integer changePercent) {}

    public record BehaviourInfo(
            String month,
            int dayOfMonth,
            int daysInMonth,
            int baselineMonths,
            boolean comparable,
            String note,
            List<CategoryInfo> categories) {}

    /** Most anomalies {@link #getOpenAnomalies} returns. */
    static final int ANOMALY_LIMIT = 5;

    private final LedgerApi ledger;
    private final InsightsApi insights;
    private final GoalsApi goals;

    AdvisorTools(LedgerApi ledger, InsightsApi insights, GoalsApi goals) {
        this.ledger = ledger;
        this.insights = insights;
        this.goals = goals;
    }

    @Tool(name = "getJars", description = "Các hũ của người dùng: mã, tên, số dư hiện tại (VND) và tỷ lệ phân bổ (%).")
    public List<JarInfo> getJars(ToolContext context) {
        return run(
                context,
                "getJars",
                () -> ledger.jars(user(context)).stream()
                        .map(j -> new JarInfo(
                                j.code(),
                                j.name(),
                                vnd(j.balance()),
                                j.targetPct().stripTrailingZeros().toPlainString()))
                        .toList());
    }

    @Tool(
            name = "getMonthlyCashflow",
            description =
                    "Tổng thu, tổng chi và chênh lệch (VND) theo từng tháng, cũ trước mới sau. Tháng hiện tại chưa"
                            + " trọn tháng.")
    public List<MonthInfo> getMonthlyCashflow(
            @ToolParam(description = "Số tháng gần nhất, từ 1 đến 12") int months, ToolContext context) {
        int lastMonths = Math.max(1, Math.min(12, months));
        return run(
                context,
                "getMonthlyCashflow",
                () -> insights.monthlyCashflow(user(context), lastMonths).stream()
                        .map(m -> new MonthInfo(m.month().toString(), vnd(m.income()), vnd(m.expense()), vnd(m.net())))
                        .toList());
    }

    @Tool(name = "getGoals", description = "Các mục tiêu tiết kiệm: tên, số tiền mục tiêu (VND), hạn, trạng thái.")
    public List<GoalInfo> getGoals(ToolContext context) {
        return run(
                context,
                "getGoals",
                () -> goals.goals(user(context)).stream()
                        .map(g -> new GoalInfo(
                                g.name(), vnd(g.target()), g.targetDate().toString(), g.status()))
                        .toList());
    }

    @Tool(
            name = "getOpenAnomalies",
            description = "Các cảnh báo chi tiêu bất thường người dùng chưa xem xét, mới nhất trước, tối đa "
                    + ANOMALY_LIMIT + " cảnh báo (totalOpen là tổng số, có thể lớn hơn). Mỗi cảnh báo: hũ, loại"
                    + " (SPIKE: một khoản chi lớn bất thường; DRIFT_UP: chi tăng đều so với thường lệ; DRIFT_DOWN:"
                    + " chi giảm đều), mức độ (LOW, MEDIUM, HIGH), số tiền khoản chi (VND), mức chi trung bình hiện"
                    + " tại của hũ (usualVnd, có thể null), ngày phát hiện và lời giải thích. Hệ thống không biết"
                    + " nguyên nhân của khoản chi.")
    public OpenAnomaliesInfo getOpenAnomalies(ToolContext context) {
        return run(context, "getOpenAnomalies", () -> {
            UserId user = user(context);
            List<AnomalyInfo> latest = insights.openAnomalies(user, ANOMALY_LIMIT).stream()
                    .map(a -> new AnomalyInfo(
                            a.jarName() == null ? "Hũ đã lưu trữ" : a.jarName(),
                            a.kind().name(),
                            a.severity(),
                            vnd(a.amount()),
                            a.usualAmount() == null ? null : vnd(a.usualAmount()),
                            a.detectedOn().toString(),
                            explanation(a.kind())))
                    .toList();
            return new OpenAnomaliesInfo(insights.openAnomalyCount(user), latest);
        });
    }

    @Tool(
            name = "getSpendingBehaviour",
            description =
                    "So chi tiêu THÁNG NÀY theo danh mục với mức trung bình hằng tháng của chính người dùng trong "
                            + InsightsApi.BASELINE_MONTHS
                            + " tháng trước (chỉ tính tháng có chi tiêu). Tháng này CHƯA trọn:"
                            + " dayOfMonth/daysInMonth cho biết đã qua bao nhiêu ngày và số tiền tháng này chưa được quy đổi"
                            + " ra cả tháng. Tối đa " + InsightsApi.MAX_CATEGORIES
                            + " danh mục có chi tiêu tháng này, nhiều"
                            + " nhất trước. usualVnd, differenceVnd và changePercent (số nguyên, %) đều null khi"
                            + " comparable=false (dưới " + InsightsApi.MIN_BASELINE_MONTHS
                            + " tháng lịch sử); changePercent"
                            + " null khi danh mục chưa từng có chi tiêu ở các tháng trước (usualVnd = 0).")
    public BehaviourInfo getSpendingBehaviour(ToolContext context) {
        return run(context, "getSpendingBehaviour", () -> {
            SpendingBehaviour b = insights.spendingBehaviour(user(context));
            String note = b.comparable()
                    ? "Tháng này mới đến ngày " + b.dayOfMonth() + "/" + b.daysInMonth()
                            + ", số tiền tháng này chưa trọn tháng; mức thường lệ tính trên các tháng đã trọn."
                    : "Chưa đủ lịch sử để so sánh: cần ít nhất " + InsightsApi.MIN_BASELINE_MONTHS
                            + " tháng trước có chi tiêu.";
            return new BehaviourInfo(
                    b.month().toString(),
                    b.dayOfMonth(),
                    b.daysInMonth(),
                    b.baselineMonths(),
                    b.comparable(),
                    note,
                    b.categories().stream()
                            .map(c -> new CategoryInfo(
                                    c.category(),
                                    vnd(c.spent()),
                                    c.usual() == null ? null : vnd(c.usual()),
                                    c.difference() == null ? null : vnd(c.difference()),
                                    c.changePercent()))
                            .toList());
        });
    }

    private static String explanation(OpenAnomaly.Kind kind) {
        return switch (kind) {
            case SPIKE -> "Một khoản chi cao hơn nhiều so với các khoản chi gần đây của hũ này.";
            case DRIFT_UP -> "Các khoản chi gần đây của hũ tăng đều so với mức thường lệ; số tiền là khoản mới nhất.";
            case DRIFT_DOWN ->
                "Các khoản chi gần đây của hũ thấp hơn đều so với mức thường lệ; số tiền là khoản mới nhất.";
        };
    }

    private static UserId user(ToolContext context) {
        return (UserId) context.getContext().get(USER);
    }

    /** Announces the tool call (SSE {@code tool} events and the number check) around the lookup; also used by {@link CatalogTools}. */
    @SuppressWarnings("unchecked")
    static <T> T run(ToolContext context, String name, java.util.function.Supplier<T> lookup) {
        BiConsumer<String, Object> events =
                (BiConsumer<String, Object>) context.getContext().get(EVENTS);
        if (events != null) {
            events.accept(name, "started");
        }
        T result = lookup.get();
        if (events != null) {
            events.accept(name, result);
        }
        return result;
    }

    private static String vnd(Money money) {
        return money.amount().stripTrailingZeros().toPlainString();
    }
}
