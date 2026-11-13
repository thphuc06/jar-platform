package io.github.thphuc06.jars.advisor.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.UserApiTestSupport;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The behaviour tools as the model calls them: through their {@link ToolCallback} with the {@link ToolContext} the
 * service fills, against a real database. The user is in the context and nowhere else, so what a tool returns is what
 * that user, and only that user, may see.
 */
class AdvisorToolsTest extends UserApiTestSupport {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    AdvisorTools tools;

    private ToolCallback tool(String name) {
        for (ToolCallback callback : ToolCallbacks.from(tools)) {
            if (callback.getToolDefinition().name().equals(name)) {
                return callback;
            }
        }
        throw new AssertionError("no tool " + name);
    }

    private JsonNode call(String name, UserId user) {
        return mapper.readTree(tool(name).call("{}", new ToolContext(Map.of(AdvisorTools.USER, user))));
    }

    private void addAnomaly(UserId user, UUID jar, String detector, String amount, Instant at) {
        jdbc.sql("""
                        INSERT INTO anomalies (id, user_id, jar_id, transaction_id, detector, direction, severity,
                                               score, threshold, amount, status, detected_at)
                        VALUES (?, ?, ?, ?, ?, 'UP', 'HIGH', 9, 3.5, ?, 'OPEN', ?)
                        """)
                .params(
                        Uuids.v7(),
                        user.value(),
                        jar,
                        UUID.randomUUID(),
                        detector,
                        new BigDecimal(amount),
                        OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }

    private void addCategorySpend(UserId user, UUID category, YearMonth month, String spent) {
        jdbc.sql("""
                        INSERT INTO monthly_category_spend (user_id, category_id, month, spent, txn_count)
                        VALUES (?, ?, ?, ?, 1)
                        """)
                .params(user.value(), category, month.atDay(1), new BigDecimal(spent))
                .update();
    }

    @Test
    void theModelIsOfferedTheFiveTools_withoutAUserParameter() {
        List<String> names = new ArrayList<>();
        for (ToolCallback callback : ToolCallbacks.from(tools)) {
            names.add(callback.getToolDefinition().name());
            assertThat(callback.getToolDefinition().inputSchema())
                    .as(
                            "%s: the user comes from the JWT, never from the model",
                            callback.getToolDefinition().name())
                    .doesNotContain("userId")
                    .doesNotContain("user");
        }

        assertThat(names)
                .containsExactlyInAnyOrder(
                        "getJars", "getMonthlyCashflow", "getGoals", "getOpenAnomalies", "getSpendingBehaviour");
        assertThat(tool("getOpenAnomalies").getToolDefinition().description())
                .as("the cap is stated")
                .contains("tối đa " + AdvisorTools.ANOMALY_LIMIT);
        assertThat(tool("getSpendingBehaviour").getToolDefinition().description())
                .contains("CHƯA trọn")
                .contains("dayOfMonth");
        assertThat(AdvisorService.SYSTEM_PROMPT)
                .contains("getOpenAnomalies", "getSpendingBehaviour", "comparable")
                .doesNotContain("getOpenAnomalyCount");
    }

    @Test
    void getOpenAnomalies_returnsTheUsersOpenAlertsWithWhatTheModelNeeds_andNothingOfAnotherUser() {
        TestUser alice = newTestUser();
        TestUser bob = newTestUser();
        UUID nec = jarId(alice.token(), "NEC");
        addAnomaly(alice.id(), nec, "MAD", "1800000", Instant.now());
        addAnomaly(bob.id(), jarId(bob.token(), "PLAY"), "MAD", "7777777", Instant.now());

        JsonNode forAlice = call("getOpenAnomalies", alice.id());
        JsonNode forBob = call("getOpenAnomalies", bob.id());

        assertThat(forAlice.get("totalOpen").asInt()).isEqualTo(1);
        JsonNode first = forAlice.get("anomalies").get(0);
        assertThat(first.get("type").asString()).isEqualTo("SPIKE");
        assertThat(first.get("severity").asString()).isEqualTo("HIGH");
        assertThat(first.get("amountVnd").asString()).isEqualTo("1800000");
        assertThat(first.get("detectedOn").asString())
                .isEqualTo(java.time.LocalDate.now(VIETNAM).toString());
        assertThat(first.get("explanation").asString()).isNotBlank();
        assertThat(forAlice.toString()).doesNotContain("7777777");
        assertThat(forBob.get("anomalies")).hasSize(1);
        assertThat(forBob.get("anomalies").get(0).get("amountVnd").asString()).isEqualTo("7777777");
        assertThat(forBob.toString()).doesNotContain("1800000");
        assertThat(call("getOpenAnomalies", newTestUser().id()).get("totalOpen").asInt())
                .isZero();
    }

    @Test
    void getOpenAnomalies_returnsAtMostFive_mostRecentFirst_andSaysHowManyThereAre() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        for (int i = 0; i < 7; i++) {
            addAnomaly(user.id(), nec, "MAD", (i + 1) + "00000", Instant.now().minusSeconds(100L * (7 - i)));
        }

        JsonNode result = call("getOpenAnomalies", user.id());

        assertThat(result.get("totalOpen").asInt()).isEqualTo(7);
        assertThat(result.get("anomalies")).hasSize(AdvisorTools.ANOMALY_LIMIT);
        assertThat(result.get("anomalies").get(0).get("amountVnd").asString()).isEqualTo("700000");
    }

    @Test
    void getSpendingBehaviour_comparesWithTheUsersOwnAverage_andNotWithAnotherUsers() {
        TestUser alice = newTestUser();
        TestUser bob = newTestUser();
        YearMonth now = YearMonth.now(VIETNAM);
        addCategorySpend(alice.id(), CATEGORY_FOOD, now, "1800000");
        addCategorySpend(alice.id(), CATEGORY_FOOD, now.minusMonths(1), "1200000");
        addCategorySpend(alice.id(), CATEGORY_FOOD, now.minusMonths(2), "1200000");
        addCategorySpend(bob.id(), CATEGORY_FOOD, now, "5555000");

        JsonNode forAlice = call("getSpendingBehaviour", alice.id());
        JsonNode forBob = call("getSpendingBehaviour", bob.id());

        assertThat(forAlice.get("comparable").asBoolean()).isTrue();
        assertThat(forAlice.get("dayOfMonth").asInt())
                .isEqualTo(java.time.LocalDate.now(VIETNAM).getDayOfMonth());
        assertThat(forAlice.get("daysInMonth").asInt()).isEqualTo(now.lengthOfMonth());
        assertThat(forAlice.get("note").asString()).contains("chưa trọn tháng");
        JsonNode food = forAlice.get("categories").get(0);
        assertThat(food.get("category").asString()).isEqualTo("Ăn uống");
        assertThat(food.get("spentVnd").asString()).isEqualTo("1800000");
        assertThat(food.get("usualVnd").asString()).isEqualTo("1200000");
        assertThat(food.get("differenceVnd").asString()).isEqualTo("600000");
        assertThat(food.get("changePercent").asInt()).isEqualTo(50);
        assertThat(forAlice.toString()).doesNotContain("5555000");

        assertThat(forBob.get("comparable").asBoolean())
                .as("bob has no history of his own")
                .isFalse();
        assertThat(forBob.get("note").asString()).contains("Chưa đủ lịch sử");
        JsonNode bobFood = forBob.get("categories").get(0);
        assertThat(bobFood.get("spentVnd").asString()).isEqualTo("5555000");
        assertThat(bobFood.get("usualVnd").isNull()).isTrue();
        assertThat(bobFood.get("changePercent").isNull()).isTrue();
    }

    @Test
    void theNewToolResultsMakeTheAmountsInAnAnswerGrounded_andPercentagesAreIgnored() {
        TestUser user = newTestUser();
        YearMonth now = YearMonth.now(VIETNAM);
        addCategorySpend(user.id(), CATEGORY_FOOD, now, "1800000");
        addCategorySpend(user.id(), CATEGORY_FOOD, now.minusMonths(1), "1200000");
        addCategorySpend(user.id(), CATEGORY_FOOD, now.minusMonths(2), "1200000");
        addAnomaly(user.id(), jarId(user.token(), "NEC"), "MAD", "2400000", Instant.now());
        // what AdvisorService collects for the number check: the user's message plus every tool result as JSON
        String sources = "Có gì bất thường không?\n"
                + call("getOpenAnomalies", user.id()) + "\n"
                + call("getSpendingBehaviour", user.id());

        Set<String> ungrounded = Grounding.ungrounded(
                "Khoản chi 2.400.000 ₫ ở hũ thiết yếu cao bất thường. Ăn uống đã 1.800.000 ₫, so với mức thường lệ"
                        + " 1.200.000 ₫, tức hơn 600.000 ₫ (khoảng 50%, hay 1.250 %).",
                sources);

        assertThat(ungrounded).isEmpty();
        assertThat(Grounding.ungrounded("Bạn nên chi thêm 900.000 ₫.", sources)).containsExactly("900000");
    }
}
