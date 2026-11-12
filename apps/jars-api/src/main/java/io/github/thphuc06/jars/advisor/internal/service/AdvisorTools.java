package io.github.thphuc06.jars.advisor.internal.service;

import io.github.thphuc06.jars.goals.GoalsApi;
import io.github.thphuc06.jars.insights.InsightsApi;
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

    @Tool(name = "getOpenAnomalyCount", description = "Số cảnh báo chi tiêu bất thường người dùng chưa xem xét.")
    public long getOpenAnomalyCount(ToolContext context) {
        return run(context, "getOpenAnomalyCount", () -> insights.openAnomalyCount(user(context)));
    }

    private static UserId user(ToolContext context) {
        return (UserId) context.getContext().get(USER);
    }

    @SuppressWarnings("unchecked")
    private static <T> T run(ToolContext context, String name, java.util.function.Supplier<T> lookup) {
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
