package io.github.thphuc06.jars.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.thphuc06.jars.ApiTestSupport;
import io.github.thphuc06.jars.ledger.TransactionPosted;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Flow F3 end to end: a posted transaction becomes a dashboard figure, an unusual expense becomes an anomaly and a
 * notification. The listeners are asynchronous (they run after the posting commits), so the tests wait for the
 * outcome with Awaitility instead of sleeping.
 */
class InsightsFlowTest extends ApiTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    // ------------------------------------------------------------------ dashboard

    @Test
    void dashboard_reflectsIncomeExpensesAndTheirBreakdowns() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID play = jarId(user, "PLAY");
        post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "10000000"));
        post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "150000", CATEGORY_FOOD, null));
        post(user, "/api/v1/transactions", newKey(), expenseBody(play, "50000", CATEGORY_TRANSPORT, null));

        JsonNode d = awaitDashboard(user, null, dash -> "200000.00".equals(amount(dash.get("expense"))));

        assertThat(amount(d.get("income"))).isEqualTo("10000000.00");
        assertThat(amount(d.get("net"))).isEqualTo("9800000.00");
        assertThat(amount(d.get("balance"))).as("sum of jar balances").isEqualTo("9800000.00");
        assertThat(d.get("spendByJar")).as("every active jar is listed").hasSize(6);
        JsonNode necSpend = jarSpend(d, nec);
        assertThat(amount(necSpend.get("spent"))).isEqualTo("150000.00");
        assertThat(necSpend.get("txnCount").asInt()).isEqualTo(1);
        assertThat(necSpend.get("name").asString()).isEqualTo("Thiết yếu");
        assertThat(d.get("spendByCategory")).hasSize(2);
        assertThat(d.get("spendByCategory").get(0).get("name").asString()).isEqualTo("Ăn uống"); // biggest first
        assertThat(d.get("trend")).hasSize(6);
        JsonNode lastMonth = d.get("trend").get(5);
        assertThat(lastMonth.get("month").asString()).isEqualTo(d.get("month").asString());
        assertThat(amount(lastMonth.get("expense"))).isEqualTo("200000.00");
        assertThat(amount(d.get("trend").get(0).get("expense"))).isEqualTo("0.00");
        assertThat(d.get("openAnomalies").asInt()).isZero();
    }

    @Test
    void dashboard_ofAnEmptyMonth_isZeroButStillListsTheJars() {
        JwtRequestPostProcessor user = newUser();
        jarId(user, "NEC");

        JsonNode d = get(user, "/api/v1/dashboard?month=2020-01").json();

        assertThat(d.get("month").asString()).isEqualTo("2020-01");
        assertThat(amount(d.get("expense"))).isEqualTo("0.00");
        assertThat(d.get("spendByJar")).hasSize(6);
        assertThat(d.get("spendByCategory")).isEmpty();
    }

    @Test
    void dashboard_withABadMonth_is400() {
        assertThat(get(newUser(), "/api/v1/dashboard?month=2026-13").status()).isEqualTo(400);
        assertThat(get(newUser(), "/api/v1/dashboard?month=nope").status()).isEqualTo(400);
    }

    @Test
    void dashboard_neverShowsAnotherUsersNumbers() {
        JwtRequestPostProcessor alice = newUser();
        JwtRequestPostProcessor bob = newUser();
        UUID aliceNec = jarId(alice, "NEC");
        jarId(bob, "NEC");
        post(alice, "/api/v1/transactions", newKey(), expenseBody(aliceNec, "777000", CATEGORY_FOOD, null));
        awaitDashboard(alice, null, dash -> "777000.00".equals(amount(dash.get("expense"))));

        JsonNode bobs = get(bob, "/api/v1/dashboard").json();

        assertThat(amount(bobs.get("expense"))).isEqualTo("0.00");
        assertThat(bobs.get("spendByCategory")).isEmpty();
    }

    @Test
    void recategorizing_movesTheSpendBetweenCategoriesAndJars() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID play = jarId(user, "PLAY");
        String txId = post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "90000", CATEGORY_FOOD, null))
                .json()
                .get("transaction")
                .get("id")
                .asString();
        awaitDashboard(
                user, null, dash -> "90000.00".equals(amount(jarSpend(dash, nec).get("spent"))));

        patch(
                user,
                "/api/v1/transactions/" + txId,
                "{\"categoryId\":\"" + CATEGORY_TRANSPORT + "\",\"jarId\":\"" + play + "\"}");

        JsonNode d = awaitDashboard(
                user,
                null,
                dash -> "90000.00".equals(amount(jarSpend(dash, play).get("spent"))));
        assertThat(amount(jarSpend(d, nec).get("spent"))).isEqualTo("0.00");
        assertThat(jarSpend(d, nec).get("txnCount").asInt()).isZero();
        assertThat(jarSpend(d, play).get("txnCount").asInt()).isEqualTo(1);
        assertThat(d.get("spendByCategory")).hasSize(1);
        assertThat(d.get("spendByCategory").get(0).get("name").asString()).isEqualTo("Di chuyển");
        assertThat(amount(d.get("expense"))).as("total spending is unchanged").isEqualTo("90000.00");
    }

    @Test
    void anExpenseWithoutACategory_isShownAsUncategorized() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        post(
                user,
                "/api/v1/transactions",
                newKey(),
                "{\"type\":\"EXPENSE\",\"amount\":12000,\"jarId\":\"" + nec + "\"}");

        JsonNode d =
                awaitDashboard(user, null, dash -> dash.get("spendByCategory").size() == 1);

        assertThat(d.get("spendByCategory").get(0).get("name").asString()).isEqualTo("Chưa phân loại");
        assertThat(d.get("spendByCategory").get(0).get("categoryId").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ anomalies

    @Test
    void aHugeExpense_becomesAnAnomalyAndANotification() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        trainSteadySpending(user, nec);

        post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "2000000", CATEGORY_FOOD, null));

        JsonNode anomalies = awaitAnomalies(user, "", 1);
        JsonNode a = anomalies.get("items").get(0);
        assertThat(a.get("detector").asString()).isEqualTo("MAD");
        assertThat(a.get("direction").asString()).isEqualTo("UP");
        assertThat(a.get("severity").asString()).isEqualTo("HIGH");
        assertThat(a.get("status").asString()).isEqualTo("OPEN");
        assertThat(a.get("jarName").asString()).isEqualTo("Thiết yếu");
        assertThat(amount(a.get("amount"))).isEqualTo("2000000.00");

        JsonNode notifications = awaitNotifications(user, "", 1);
        JsonNode n = notifications.get("items").get(0);
        assertThat(n.get("type").asString()).isEqualTo("ANOMALY");
        assertThat(n.get("severity").asString()).isEqualTo("HIGH");
        assertThat(n.get("title").asString()).contains("Thiết yếu").contains("bất thường");
        assertThat(n.get("body").asString()).contains("2.000.000");
        assertThat(n.get("refId").asString()).isEqualTo(a.get("id").asString());
        assertThat(n.get("readAt").isNull()).isTrue();
        assertThat(notifications.get("unreadCount").asInt()).isEqualTo(1);
        assertThat(get(user, "/api/v1/dashboard").json().get("openAnomalies").asInt())
                .isEqualTo(1);
    }

    @Test
    void normalSpending_neverRaisesAnAlert() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");

        trainSteadySpending(user, nec);

        assertThat(get(user, "/api/v1/anomalies").json().get("items")).isEmpty();
        assertThat(get(user, "/api/v1/notifications").json().get("items")).isEmpty();
    }

    @Test
    void feedback_updatesTheStatus_andOnlyOwnersCanGiveIt() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        trainSteadySpending(user, nec);
        post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "2000000", CATEGORY_FOOD, null));
        String id = awaitAnomalies(user, "", 1).get("items").get(0).get("id").asString();
        JwtRequestPostProcessor stranger = newUser();

        assertThat(post(stranger, "/api/v1/anomalies/" + id + "/feedback", null, "{\"label\":\"CONFIRMED\"}")
                        .status())
                .isEqualTo(404);
        assertThat(post(user, "/api/v1/anomalies/" + id + "/feedback", null, "{\"label\":\"OPEN\"}")
                        .status())
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/anomalies/" + id + "/feedback", null, "{\"label\":\"MAYBE\"}")
                        .status())
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/anomalies/" + id + "/feedback", null, "{}")
                        .status())
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/anomalies/" + UUID.randomUUID() + "/feedback", null, "{\"label\":\"EXPECTED\"}")
                        .status())
                .isEqualTo(404);

        Resp ok = post(user, "/api/v1/anomalies/" + id + "/feedback", null, "{\"label\":\"EXPECTED\"}");

        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.json().get("status").asString()).isEqualTo("EXPECTED");
        assertThat(get(user, "/api/v1/anomalies?status=OPEN").json().get("items"))
                .isEmpty();
        assertThat(get(user, "/api/v1/anomalies?status=EXPECTED").json().get("items"))
                .hasSize(1);
        assertThat(get(user, "/api/v1/dashboard").json().get("openAnomalies").asInt())
                .isZero();
        assertThat(get(stranger, "/api/v1/anomalies").json().get("items"))
                .as("strangers see none")
                .isEmpty();
    }

    @Test
    void anomalies_pageWithAKeysetCursor() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        trainSteadySpending(user, nec);
        for (int i = 0; i < 5; i++) {
            post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "2000000", CATEGORY_FOOD, null));
        }
        awaitAnomalies(user, "", 5);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = get(user, "/api/v1/anomalies?limit=2" + (cursor == null ? "" : "&cursor=" + cursor))
                    .json();
            page.get("items").forEach(a -> seen.add(a.get("id").asString()));
            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        assertThat(get(user, "/api/v1/anomalies?limit=0").status()).isEqualTo(400);
        assertThat(get(user, "/api/v1/anomalies?cursor=garbage").status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ notifications

    @Test
    void notifications_canBeReadOnce_andBelongToTheirOwner() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        trainSteadySpending(user, nec);
        post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "2000000", CATEGORY_FOOD, null));
        String id =
                awaitNotifications(user, "", 1).get("items").get(0).get("id").asString();
        JwtRequestPostProcessor stranger = newUser();

        assertThat(post(stranger, "/api/v1/notifications/" + id + "/read", null, null)
                        .status())
                .isEqualTo(404);
        Resp first = post(user, "/api/v1/notifications/" + id + "/read", null, null);
        Resp second = post(user, "/api/v1/notifications/" + id + "/read", null, null);

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().get("readAt").isNull()).isFalse();
        assertThat(second.json().get("readAt"))
                .as("reading again keeps the first time")
                .isEqualTo(first.json().get("readAt"));
        JsonNode list = get(user, "/api/v1/notifications").json();
        assertThat(list.get("unreadCount").asInt()).isZero();
        assertThat(get(user, "/api/v1/notifications?unreadOnly=true").json().get("items"))
                .isEmpty();
        assertThat(get(stranger, "/api/v1/notifications").json().get("items")).isEmpty();
    }

    // ------------------------------------------------------------------ at-least-once delivery

    @Test
    void aRedeliveredEvent_isCountedOnce() throws Exception {
        UserId user = new UserId(UUID.randomUUID());
        UUID jar = UUID.randomUUID();
        TransactionPosted first = expense(user, jar, "1000");
        TransactionPosted marker = expense(user, jar, "2000");

        tx.executeWithoutResult(s -> {
            publisher.publishEvent(first);
            publisher.publishEvent(first); // same eventId: the broker delivered it twice
            publisher.publishEvent(marker);
        });

        await().atMost(WAIT).until(() -> txnCount(jar) >= 2);
        Thread.sleep(1500); // give a wrongly counted duplicate time to show up
        assertThat(txnCount(jar))
                .as("first + marker, the duplicate was skipped")
                .isEqualTo(2);
        assertThat(spent(jar)).isEqualByComparingTo("3000");
    }

    // ------------------------------------------------------------------ helpers

    /** Twelve ordinary expenses around 100,000, so the detector has a baseline; waits until all are processed. */
    private void trainSteadySpending(JwtRequestPostProcessor user, UUID jar) {
        int[] amounts = {
            95_000, 105_000, 98_000, 102_000, 100_000, 97_000, 103_000, 99_000, 101_000, 100_000, 96_000, 104_000
        };
        for (int amount : amounts) {
            assertThat(post(
                                    user,
                                    "/api/v1/transactions",
                                    newKey(),
                                    expenseBody(jar, String.valueOf(amount), CATEGORY_FOOD, null))
                            .status())
                    .isEqualTo(201);
        }
        awaitDashboard(user, null, d -> jarSpend(d, jar).get("txnCount").asInt() == amounts.length);
    }

    private JsonNode awaitDashboard(
            JwtRequestPostProcessor user, String month, java.util.function.Predicate<JsonNode> ready) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            last[0] = get(user, "/api/v1/dashboard" + (month == null ? "" : "?month=" + month))
                    .json();
            return ready.test(last[0]);
        });
        return last[0];
    }

    private JsonNode awaitAnomalies(JwtRequestPostProcessor user, String query, int expected) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            last[0] = get(user, "/api/v1/anomalies?limit=100" + query).json();
            return last[0].get("items").size() >= expected;
        });
        return last[0];
    }

    private JsonNode awaitNotifications(JwtRequestPostProcessor user, String query, int expected) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            last[0] = get(user, "/api/v1/notifications?limit=100" + query).json();
            return last[0].get("items").size() >= expected;
        });
        return last[0];
    }

    private static JsonNode jarSpend(JsonNode dashboard, UUID jarId) {
        for (JsonNode j : dashboard.get("spendByJar")) {
            if (j.get("jarId").asString().equals(jarId.toString())) {
                return j;
            }
        }
        throw new AssertionError("jar " + jarId + " not in dashboard");
    }

    private static String amount(JsonNode money) {
        return money.get("amount").asString();
    }

    private static TransactionPosted expense(UserId user, UUID jar, String amount) {
        return new TransactionPosted(
                Uuids.v7(), UUID.randomUUID(), user, "EXPENSE", Money.vnd(amount), jar, null, Instant.now(), "MANUAL");
    }

    private int txnCount(UUID jar) {
        return jdbc.sql("SELECT COALESCE(SUM(txn_count), 0) FROM monthly_jar_spend WHERE jar_id = ?")
                .param(jar)
                .query(Integer.class)
                .single();
    }

    private java.math.BigDecimal spent(UUID jar) {
        return jdbc.sql("SELECT COALESCE(SUM(spent), 0) FROM monthly_jar_spend WHERE jar_id = ?")
                .param(jar)
                .query(java.math.BigDecimal.class)
                .single();
    }
}
