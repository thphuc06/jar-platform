package io.github.thphuc06.jars.budgeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.thphuc06.jars.UserApiTestSupport;
import io.github.thphuc06.jars.shared.Uuids;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The budgeting endpoints end to end: suggestions, applying a split, and the planned-against-spent view. */
class BudgetingApiTest extends UserApiTestSupport {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String SUGGESTIONS = "/api/v1/budget/allocation-suggestions";
    private static final String ALLOCATIONS = "/api/v1/budget/allocations";

    // ---------------------------------------------------------------- helpers

    private static YearMonth thisMonth() {
        return YearMonth.now(VIETNAM);
    }

    private static String amountBody(String amount) {
        return "{\"amount\":" + amount + "}";
    }

    private static String split(UUID jar, String amount) {
        return "{\"jarId\":\"" + jar + "\",\"amount\":" + amount + "}";
    }

    private static String allocation(String amount, String... splits) {
        return "{\"amount\":" + amount + ",\"splits\":[" + String.join(",", splits) + "]}";
    }

    private static String allocationAt(String amount, String occurredAt, String... splits) {
        return "{\"amount\":" + amount + ",\"occurredAt\":\"" + occurredAt + "\",\"splits\":["
                + String.join(",", splits) + "]}";
    }

    private static String moneyOf(JsonNode money) {
        return money.get("amount").asString();
    }

    private static String atMiddayOf(YearMonth month) {
        return month.atDay(15).atTime(12, 0).atZone(VIETNAM).toInstant().toString();
    }

    private BigDecimal planned(UUID jar, YearMonth month) {
        return jdbc.sql(
                        "SELECT COALESCE(SUM(planned_amount), 0) FROM jar_periods WHERE jar_id = ? AND period_month = ?")
                .params(jar, month.atDay(1))
                .query(BigDecimal.class)
                .single();
    }

    private int decisions(TestUser user) {
        return jdbc.sql("SELECT count(*) FROM allocation_decisions WHERE user_id = ?")
                .param(user.id().value())
                .query(Integer.class)
                .single();
    }

    private int incomes(TestUser user) {
        return jdbc.sql("SELECT count(*) FROM transactions WHERE user_id = ? AND type = 'INCOME'")
                .param(user.id().value())
                .query(Integer.class)
                .single();
    }

    private void setSpent(TestUser user, UUID jar, YearMonth month, String amount) {
        jdbc.sql("""
                        INSERT INTO jar_periods (id, jar_id, user_id, period_month, spent_amount, created_at)
                        VALUES (?, ?, ?, ?, ?, now())
                        """)
                .params(Uuids.v7(), jar, user.id().value(), month.atDay(1), new BigDecimal(amount))
                .update();
    }

    private JsonNode jarLine(JsonNode lines, UUID jar) {
        for (JsonNode line : lines) {
            if (line.get("jarId").asString().equals(jar.toString())) {
                return line;
            }
        }
        throw new AssertionError("jar " + jar + " is not in " + lines);
    }

    private void assertAllJarsBalanced(TestUser user) {
        for (JsonNode jar : get(user.token(), "/api/v1/jars").json()) {
            assertBalanceMatchesEntries(UUID.fromString(jar.get("id").asString()));
        }
    }

    private interface Task {
        Resp run(int index);
    }

    private List<Resp> runConcurrently(int count, Task task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Resp>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.run(index);
                }));
            }
            start.countDown();
            List<Resp> results = new ArrayList<>();
            for (Future<Resp> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------ suggestions

    @Test
    void suggestion_forTheDefaultJars_followsThePlannedPercentages() {
        TestUser user = newTestUser();

        Resp resp = post(user.token(), SUGGESTIONS, null, amountBody("10000000"));

        assertThat(resp.status()).isEqualTo(200);
        JsonNode s = resp.json();
        assertThat(moneyOf(s.get("amount"))).isEqualTo("10000000.00");
        assertThat(s.get("monthsOfHistory").asInt()).isZero();
        assertThat(s.get("historyWeight").decimalValue()).isEqualByComparingTo("0");
        JsonNode lines = s.get("lines");
        assertThat(lines).hasSize(6);
        assertThat(jarLine(lines, jarId(user.token(), "NEC")).get("name").asString())
                .isEqualTo("Thiết yếu");
        assertThat(moneyOf(jarLine(lines, jarId(user.token(), "NEC")).get("amount")))
                .isEqualTo("5500000.00");
        assertThat(moneyOf(jarLine(lines, jarId(user.token(), "FFA")).get("amount")))
                .isEqualTo("1000000.00");
        assertThat(moneyOf(jarLine(lines, jarId(user.token(), "GIVE")).get("amount")))
                .isEqualTo("500000.00");
        assertThat(jarLine(lines, jarId(user.token(), "NEC")).get("sharePct").decimalValue())
                .isEqualByComparingTo("55");
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal pct = BigDecimal.ZERO;
        for (JsonNode line : lines) {
            total = total.add(new BigDecimal(moneyOf(line.get("amount"))));
            pct = pct.add(line.get("sharePct").decimalValue());
        }
        assertThat(total).isEqualByComparingTo("10000000");
        assertThat(pct).isEqualByComparingTo("100");
    }

    @Test
    void suggestion_alwaysAddsUpToTheAmount_evenWhenItDoesNotDivideEvenly() {
        TestUser user = newTestUser();

        for (String amount : List.of("0.01", "1", "99.99", "1000000.07", "12345678.91")) {
            JsonNode lines = post(user.token(), SUGGESTIONS, null, amountBody(amount))
                    .json()
                    .get("lines");
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode line : lines) {
                total = total.add(new BigDecimal(moneyOf(line.get("amount"))));
            }
            assertThat(total).as(amount).isEqualByComparingTo(amount);
        }
    }

    @Test
    void suggestion_isNudgedBySpendingOfTheLastMonths() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID play = jarId(user.token(), "PLAY");
        for (int back = 1; back <= 3; back++) {
            setSpent(user, nec, thisMonth().minusMonths(back), "3000000");
            setSpent(user, play, thisMonth().minusMonths(back), "9000000");
        }

        JsonNode s =
                post(user.token(), SUGGESTIONS, null, amountBody("10000000")).json();

        // three months of history: weight 0.1. Spent shares among the planned jars: NEC 25%, PLAY 75%. So
        // NEC = 0.9 * 0.55 + 0.1 * 0.25 = 0.52 and PLAY = 0.9 * 0.10 + 0.1 * 0.75 = 0.165; the others lose 10% of
        // theirs
        assertThat(s.get("monthsOfHistory").asInt()).isEqualTo(3);
        assertThat(s.get("historyWeight").decimalValue()).isEqualByComparingTo("0.1");
        JsonNode lines = s.get("lines");
        assertThat(moneyOf(jarLine(lines, nec).get("amount"))).isEqualTo("5200000.00");
        assertThat(moneyOf(jarLine(lines, play).get("amount"))).isEqualTo("1650000.00");
        assertThat(moneyOf(jarLine(lines, jarId(user.token(), "FFA")).get("amount")))
                .isEqualTo("900000.00");
        assertThat(moneyOf(jarLine(lines, jarId(user.token(), "GIVE")).get("amount")))
                .isEqualTo("450000.00");
        assertThat(jarLine(lines, nec).get("targetPct").decimalValue())
                .as("the planned percentage is shown next to the suggested one")
                .isEqualByComparingTo("55");
    }

    @Test
    void suggestion_ignoresTheRunningMonthAndMonthsOlderThanSix() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        setSpent(user, nec, thisMonth(), "9999999");
        setSpent(user, nec, thisMonth().minusMonths(7), "9999999");

        JsonNode s = post(user.token(), SUGGESTIONS, null, amountBody("1000")).json();

        assertThat(s.get("monthsOfHistory").asInt()).isZero();
        assertThat(moneyOf(jarLine(s.get("lines"), nec).get("amount"))).isEqualTo("550.00");
    }

    @Test
    void suggestion_leavesACustomJarWithoutAPlanAtZero_andSkipsArchivedJars() {
        TestUser user = newTestUser();
        UUID give = jarId(user.token(), "GIVE");
        assertThat(delete(user.token(), "/api/v1/jars/" + give).status()).isEqualTo(204);
        Resp custom = post(user.token(), "/api/v1/jars", null, "{\"name\":\"Du lịch\"}");
        assertThat(custom.status()).isEqualTo(201);
        UUID customId = UUID.fromString(custom.json().get("id").asString());

        JsonNode lines = post(user.token(), SUGGESTIONS, null, amountBody("950000"))
                .json()
                .get("lines");

        assertThat(lines).hasSize(6); // five default jars (GIVE archived) plus the custom one
        assertThat(moneyOf(jarLine(lines, customId).get("amount"))).isEqualTo("0.00");
        // the remaining planned percentages add up to 95, so they are scaled up to fill 100%
        assertThat(moneyOf(jarLine(lines, jarId(user.token(), "NEC")).get("amount")))
                .isEqualTo("550000.00");
        for (JsonNode line : lines) {
            assertThat(line.get("jarId").asString()).isNotEqualTo(give.toString());
        }
    }

    @Test
    void suggestion_withAnInvalidAmount_isRejected() {
        TestUser user = newTestUser();

        for (String body : List.of(amountBody("0"), amountBody("-5"), amountBody("1.234"), "{}")) {
            assertThat(post(user.token(), SUGGESTIONS, null, body).status())
                    .as(body)
                    .isEqualTo(422);
        }
        assertThat(post(user.token(), SUGGESTIONS, null, "{\"amount\":\"abc\"}").status())
                .isEqualTo(400);
        assertThat(post(user.token(), SUGGESTIONS, null, "not json").status()).isEqualTo(400);
    }

    @Test
    void suggestion_writesNothing() {
        TestUser user = newTestUser();

        post(user.token(), SUGGESTIONS, null, amountBody("5000000"));

        assertThat(decisions(user)).isZero();
        assertThat(incomes(user)).isZero();
    }

    // -------------------------------------------------------------- applying

    @Test
    void applying_theSuggestedSplit_postsTheIncomeAndRecordsAnAcceptedDecision() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        JsonNode suggestion =
                post(user.token(), SUGGESTIONS, null, amountBody("10000000")).json();
        List<String> splits = new ArrayList<>();
        for (JsonNode line : suggestion.get("lines")) {
            splits.add(split(UUID.fromString(line.get("jarId").asString()), moneyOf(line.get("amount"))));
        }

        Resp resp = post(user.token(), ALLOCATIONS, newKey(), allocation("10000000", splits.toArray(String[]::new)));

        assertThat(resp.status()).isEqualTo(201);
        assertThat(resp.header("Idempotency-Replayed")).isEqualTo("false");
        JsonNode body = resp.json();
        assertThat(body.get("decision").asString()).isEqualTo("ACCEPTED");
        assertThat(body.get("month").asString()).isEqualTo(thisMonth().toString());
        assertThat(moneyOf(body.get("amount"))).isEqualTo("10000000.00");
        assertThat(body.get("splits")).hasSize(6);
        assertThat(body.get("transactionId").isNull()).isFalse();
        assertThat(balanceOf(nec)).isEqualByComparingTo("5500000");
        assertThat(balanceOf(edu)).isEqualByComparingTo("1000000");
        assertAllJarsBalanced(user);
        assertThat(planned(nec, thisMonth())).isEqualByComparingTo("5500000");
        assertThat(planned(edu, thisMonth())).isEqualByComparingTo("1000000");
        assertThat(decisions(user)).isEqualTo(1);
        assertThat(incomes(user)).isEqualTo(1);

        JsonNode period =
                get(user.token(), "/api/v1/budget/periods/" + thisMonth()).json();
        assertThat(moneyOf(period.get("planned"))).isEqualTo("10000000.00");
        assertThat(moneyOf(jarLine(period.get("jars"), nec).get("planned"))).isEqualTo("5500000.00");
    }

    @Test
    void applying_anEditedSplit_isRecordedAsEdited() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID play = jarId(user.token(), "PLAY");

        Resp resp = post(
                user.token(),
                ALLOCATIONS,
                newKey(),
                allocation("1000000", split(nec, "700000"), split(play, "300000")));

        assertThat(resp.status()).isEqualTo(201);
        assertThat(resp.json().get("decision").asString()).isEqualTo("EDITED");
        assertThat(balanceOf(nec)).isEqualByComparingTo("700000");
        assertThat(balanceOf(play)).isEqualByComparingTo("300000");
        assertThat(jdbc.sql("SELECT decision FROM allocation_decisions WHERE user_id = ?")
                        .param(user.id().value())
                        .query(String.class)
                        .single())
                .isEqualTo("EDITED");
    }

    @Test
    void applying_aSplitThatLeavesOutAJarTheSuggestionGaveNothing_isStillAccepted() {
        TestUser user = newTestUser();
        post(user.token(), "/api/v1/jars", null, "{\"name\":\"Du lịch\"}"); // planned at 0%: the suggestion gives it 0
        JsonNode lines = post(user.token(), SUGGESTIONS, null, amountBody("10000000"))
                .json()
                .get("lines");
        List<String> splits = new ArrayList<>();
        for (JsonNode line : lines) {
            if (new BigDecimal(moneyOf(line.get("amount"))).signum() > 0) {
                splits.add(split(UUID.fromString(line.get("jarId").asString()), moneyOf(line.get("amount"))));
            }
        }

        Resp resp = post(user.token(), ALLOCATIONS, newKey(), allocation("10000000", splits.toArray(String[]::new)));

        assertThat(resp.status()).isEqualTo(201);
        assertThat(resp.json().get("decision").asString()).isEqualTo("ACCEPTED");
    }

    @Test
    void applying_toThePreviousMonth_addsThePlannedAmountToThatMonth() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        YearMonth lastMonth = thisMonth().minusMonths(1);

        Resp resp = post(
                user.token(),
                ALLOCATIONS,
                newKey(),
                allocationAt("500000", atMiddayOf(lastMonth), split(nec, "500000")));

        assertThat(resp.status()).isEqualTo(201);
        assertThat(resp.json().get("month").asString()).isEqualTo(lastMonth.toString());
        assertThat(planned(nec, lastMonth)).isEqualByComparingTo("500000");
        assertThat(planned(nec, thisMonth())).isEqualByComparingTo("0");
        JsonNode period =
                get(user.token(), "/api/v1/budget/periods/" + lastMonth).json();
        assertThat(moneyOf(jarLine(period.get("jars"), nec).get("planned"))).isEqualTo("500000.00");
    }

    @Test
    void skipping_recordsTheDecisionAndPostsNothing() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        String key = newKey();

        Resp resp = post(user.token(), ALLOCATIONS, key, "{\"amount\":8000000,\"skip\":true}");

        assertThat(resp.status()).isEqualTo(201);
        assertThat(resp.json().get("decision").asString()).isEqualTo("SKIPPED");
        assertThat(resp.json().get("transactionId").isNull()).isTrue();
        assertThat(resp.json().get("splits")).isEmpty();
        assertThat(incomes(user)).isZero();
        assertThat(balanceOf(nec)).isEqualByComparingTo("0");
        assertThat(planned(nec, thisMonth())).isEqualByComparingTo("0");
        assertThat(decisions(user)).isEqualTo(1);

        Resp again = post(user.token(), ALLOCATIONS, key, "{\"amount\":8000000,\"skip\":true}");
        assertThat(again.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(again.json().get("decisionId")).isEqualTo(resp.json().get("decisionId"));
        assertThat(decisions(user)).isEqualTo(1);
    }

    // ------------------------------------------------------------ validation

    @Test
    void applying_invalidRequests_areRejected_andNothingChanges() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        UUID stranger = jarId(newTestUser().token(), "NEC");

        record Case(String name, String body, int status, String problem) {}
        List<Case> cases = List.of(
                new Case("zero amount", allocation("0", split(nec, "0")), 422, null),
                new Case("negative amount", allocation("-100", split(nec, "-100")), 422, null),
                new Case("three decimals", allocation("1000.123", split(nec, "1000.123")), 422, null),
                new Case("missing amount", "{\"splits\":[" + split(nec, "100") + "]}", 422, null),
                new Case("no splits", amountBody("1000"), 422, "invalid-allocation"),
                new Case("empty splits", "{\"amount\":1000,\"splits\":[]}", 422, "invalid-allocation"),
                new Case(
                        "splits that do not add up",
                        allocation("1000", split(nec, "600"), split(edu, "399")),
                        422,
                        "allocation-sum-mismatch"),
                new Case(
                        "splits beyond the amount",
                        allocation("1000", split(nec, "600"), split(edu, "401")),
                        422,
                        "allocation-sum-mismatch"),
                new Case("a negative split", allocation("1000", split(nec, "1500"), split(edu, "-500")), 422, null),
                new Case("a zero split", allocation("1000", split(nec, "1000"), split(edu, "0")), 422, null),
                new Case("the same jar twice", allocation("1000", split(nec, "500"), split(nec, "500")), 422, null),
                new Case("a jar that does not exist", allocation("1000", split(UUID.randomUUID(), "1000")), 404, null),
                new Case(
                        "a jar of someone else",
                        allocation("1000", split(nec, "500"), split(stranger, "500")),
                        404,
                        null),
                new Case("a split without a jar", "{\"amount\":1000,\"splits\":[{\"amount\":1000}]}", 422, null),
                new Case(
                        "skip together with splits",
                        "{\"amount\":1000,\"skip\":true,\"splits\":[" + split(nec, "1000") + "]}",
                        422,
                        "invalid-allocation"),
                new Case(
                        "an expense category on an income",
                        "{\"amount\":1000,\"categoryId\":\"" + CATEGORY_FOOD + "\",\"splits\":[" + split(nec, "1000")
                                + "]}",
                        422,
                        null));

        for (Case c : cases) {
            Resp resp = post(user.token(), ALLOCATIONS, newKey(), c.body());
            assertThat(resp.status()).as(c.name()).isEqualTo(c.status());
            if (c.problem() != null) {
                assertThat(resp.json().get("type").asString()).as(c.name()).endsWith(c.problem());
            }
        }
        assertThat(post(user.token(), ALLOCATIONS, newKey(), "not json").status())
                .isEqualTo(400);
        assertThat(post(user.token(), ALLOCATIONS, newKey(), "{\"amount\":\"abc\",\"splits\":[]}")
                        .status())
                .isEqualTo(400);

        assertThat(decisions(user)).isZero();
        assertThat(incomes(user)).isZero();
        assertThat(balanceOf(nec)).isEqualByComparingTo("0");
        assertThat(balanceOf(edu)).isEqualByComparingTo("0");
        assertThat(planned(nec, thisMonth())).isEqualByComparingTo("0");
    }

    @Test
    void applying_withoutAValidIdempotencyKey_is400() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        String body = allocation("1000", split(nec, "1000"));

        assertThat(post(user.token(), ALLOCATIONS, null, body).status()).isEqualTo(400);
        assertThat(post(user.token(), ALLOCATIONS, "short", body).status()).isEqualTo(400);
        assertThat(post(user.token(), ALLOCATIONS, "bad key with spaces!", body).status())
                .isEqualTo(400);
        assertThat(post(user.token(), ALLOCATIONS, "short", "{\"amount\":1000,\"skip\":true}")
                        .status())
                .isEqualTo(400);
        assertThat(balanceOf(nec)).isEqualByComparingTo("0");
        assertThat(decisions(user)).isZero();
    }

    @Test
    void periods_withABadMonth_is400() {
        TestUser user = newTestUser();

        assertThat(get(user.token(), "/api/v1/budget/periods/2026-13").status()).isEqualTo(400);
        assertThat(get(user.token(), "/api/v1/budget/periods/nope").status()).isEqualTo(400);
        assertThat(get(user.token(), "/api/v1/budget/periods/2026-1").status()).isEqualTo(400);
    }

    // ----------------------------------------------------------- idempotency

    @Test
    void sameKeyTwice_allocatesOnce_andReplaysTheFirstResponse() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        String key = newKey();
        String body = allocation("1000000", split(nec, "600000"), split(edu, "400000"));

        Resp first = post(user.token(), ALLOCATIONS, key, body);
        Resp second = post(user.token(), ALLOCATIONS, key, body);

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.header("Idempotency-Replayed")).isEqualTo("false");
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(second.json().get("decisionId")).isEqualTo(first.json().get("decisionId"));
        assertThat(second.json().get("transactionId")).isEqualTo(first.json().get("transactionId"));
        assertThat(second.json().get("decision")).isEqualTo(first.json().get("decision"));
        assertThat(balanceOf(nec)).as("credited once").isEqualByComparingTo("600000");
        assertThat(balanceOf(edu)).isEqualByComparingTo("400000");
        assertThat(planned(nec, thisMonth())).as("planned once").isEqualByComparingTo("600000");
        assertThat(decisions(user)).isEqualTo(1);
        assertThat(incomes(user)).isEqualTo(1);
        assertAllJarsBalanced(user);
    }

    @Test
    void theSameSplitInAnotherOrderOrWithAnotherSpelling_isTheSameRequest() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        String key = newKey();

        post(user.token(), ALLOCATIONS, key, allocation("1000", split(nec, "600"), split(edu, "400")));
        Resp again =
                post(user.token(), ALLOCATIONS, key, allocation("1000.00", split(edu, "400.0"), split(nec, "600.00")));

        assertThat(again.status()).isEqualTo(201);
        assertThat(again.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(balanceOf(nec)).isEqualByComparingTo("600");
        assertThat(decisions(user)).isEqualTo(1);
    }

    @Test
    void sameKeyForADifferentRequest_is422_andChangesNothing() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        String key = newKey();
        post(user.token(), ALLOCATIONS, key, allocation("1000", split(nec, "1000")));

        Resp otherAmount = post(user.token(), ALLOCATIONS, key, allocation("2000", split(nec, "2000")));
        Resp otherJar = post(user.token(), ALLOCATIONS, key, allocation("1000", split(edu, "1000")));
        Resp skipped = post(user.token(), ALLOCATIONS, key, "{\"amount\":1000,\"skip\":true}");

        for (Resp resp : List.of(otherAmount, otherJar, skipped)) {
            assertThat(resp.status()).isEqualTo(422);
            assertThat(resp.json().get("type").asString()).endsWith("idempotency-key-reused");
        }
        assertThat(balanceOf(nec)).isEqualByComparingTo("1000");
        assertThat(balanceOf(edu)).isEqualByComparingTo("0");
        assertThat(decisions(user)).isEqualTo(1);
        assertThat(planned(nec, thisMonth())).isEqualByComparingTo("1000");
    }

    @Test
    void aRejectedAllocation_doesNotConsumeItsKey_soTheClientCanFixAndRetry() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        String key = newKey();

        Resp bad = post(user.token(), ALLOCATIONS, key, allocation("1000", split(nec, "999")));
        Resp fixed = post(user.token(), ALLOCATIONS, key, allocation("1000", split(nec, "1000")));

        assertThat(bad.status()).isEqualTo(422);
        assertThat(fixed.status()).isEqualTo(201);
        assertThat(fixed.header("Idempotency-Replayed")).isEqualTo("false");
        assertThat(decisions(user)).isEqualTo(1);
    }

    @Test
    void ifTheDecisionWasNotRecorded_sendingTheRequestAgainCompletesIt() {
        // simulates a crash between the two steps: the ledger posted the income, the decision row is missing
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        String key = newKey();
        String body = allocation("1000", split(nec, "1000"));
        Resp first = post(user.token(), ALLOCATIONS, key, body);
        jdbc.sql("DELETE FROM allocation_decisions WHERE user_id = ?")
                .param(user.id().value())
                .update();
        jdbc.sql("UPDATE jar_periods SET planned_amount = 0 WHERE jar_id = ?")
                .param(nec)
                .update();

        Resp retry = post(user.token(), ALLOCATIONS, key, body);

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.json().get("transactionId")).isEqualTo(first.json().get("transactionId"));
        assertThat(retry.header("Idempotency-Replayed"))
                .as("the decision is new, so this is the first complete answer")
                .isEqualTo("false");
        assertThat(balanceOf(nec)).as("the ledger posted only once").isEqualByComparingTo("1000");
        assertThat(incomes(user)).isEqualTo(1);
        assertThat(decisions(user)).isEqualTo(1);
        assertThat(planned(nec, thisMonth())).isEqualByComparingTo("1000");
    }

    // ----------------------------------------------------------- concurrency

    @Test
    void parallelAllocationsWithDifferentKeys_keepTheLedgerAndThePlannedTotalsConsistent() throws Exception {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        UUID play = jarId(user.token(), "PLAY");
        int writers = 6;

        List<Resp> responses = runConcurrently(
                writers,
                i -> post(
                        user.token(),
                        ALLOCATIONS,
                        newKey(),
                        allocation("600000", split(nec, "300000"), split(edu, "200000"), split(play, "100000"))));

        List<Resp> created = responses.stream().filter(r -> r.status() == 201).toList();
        long conflicts = responses.stream().filter(r -> r.status() == 409).count();
        assertThat(created.size() + conflicts)
                .as("only success or a clean conflict")
                .isEqualTo(writers);
        assertThat(created.size()).as("optimistic retries let writers through").isGreaterThanOrEqualTo(2);
        int accepted = created.size();
        // every accepted allocation is in the balances, in the entries, in the decisions and in the planned amounts
        assertThat(balanceOf(nec)).isEqualByComparingTo(BigDecimal.valueOf(300_000L * accepted));
        assertThat(balanceOf(edu)).isEqualByComparingTo(BigDecimal.valueOf(200_000L * accepted));
        assertThat(balanceOf(play)).isEqualByComparingTo(BigDecimal.valueOf(100_000L * accepted));
        assertThat(planned(nec, thisMonth())).isEqualByComparingTo(BigDecimal.valueOf(300_000L * accepted));
        assertThat(planned(edu, thisMonth())).isEqualByComparingTo(BigDecimal.valueOf(200_000L * accepted));
        assertThat(planned(play, thisMonth())).isEqualByComparingTo(BigDecimal.valueOf(100_000L * accepted));
        assertThat(decisions(user)).isEqualTo(accepted);
        assertThat(incomes(user)).isEqualTo(accepted);
        Set<String> decisionIds = new HashSet<>();
        created.forEach(r -> decisionIds.add(r.json().get("decisionId").asString()));
        assertThat(decisionIds).hasSize(accepted);
        assertAllJarsBalanced(user);
    }

    @Test
    void parallelRequestsWithTheSameKey_allocateExactlyOnce() throws Exception {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        String key = newKey();
        String body = allocation("1000000", split(nec, "700000"), split(edu, "300000"));

        List<Resp> responses = runConcurrently(6, i -> post(user.token(), ALLOCATIONS, key, body));

        assertThat(responses).extracting(Resp::status).containsOnly(201);
        assertThat(responses)
                .extracting(r -> r.json().get("decisionId").asString())
                .containsOnly(responses.getFirst().json().get("decisionId").asString());
        assertThat(responses.stream()
                        .filter(r -> "false".equals(r.header("Idempotency-Replayed")))
                        .count())
                .isEqualTo(1);
        assertThat(balanceOf(nec)).isEqualByComparingTo("700000");
        assertThat(balanceOf(edu)).isEqualByComparingTo("300000");
        assertThat(planned(nec, thisMonth())).isEqualByComparingTo("700000");
        assertThat(decisions(user)).isEqualTo(1);
        assertThat(incomes(user)).isEqualTo(1);
        assertAllJarsBalanced(user);
    }

    // ------------------------------------------------------------------ IDOR

    @Test
    void anotherUser_cannotAllocateIntoMyJars_orSeeMyPlannedMoney() {
        TestUser alice = newTestUser();
        TestUser bob = newTestUser();
        UUID aliceNec = jarId(alice.token(), "NEC");
        UUID bobNec = jarId(bob.token(), "NEC");
        String aliceKey = newKey();
        post(alice.token(), ALLOCATIONS, aliceKey, allocation("1000000", split(aliceNec, "1000000")));

        Resp intoAlicesJar = post(bob.token(), ALLOCATIONS, newKey(), allocation("500", split(aliceNec, "500")));
        Resp mixed = post(
                bob.token(), ALLOCATIONS, newKey(), allocation("500", split(bobNec, "250"), split(aliceNec, "250")));

        assertThat(intoAlicesJar.status()).isEqualTo(404);
        assertThat(mixed.status()).isEqualTo(404);
        assertThat(balanceOf(aliceNec)).isEqualByComparingTo("1000000");
        assertThat(balanceOf(bobNec)).isEqualByComparingTo("0");
        assertThat(decisions(bob)).isZero();
        assertThat(incomes(bob)).isZero();
        assertThat(planned(aliceNec, thisMonth())).isEqualByComparingTo("1000000");
        JsonNode bobsMonth =
                get(bob.token(), "/api/v1/budget/periods/" + thisMonth()).json();
        assertThat(moneyOf(bobsMonth.get("planned"))).isEqualTo("0.00");
        for (JsonNode jar : bobsMonth.get("jars")) {
            assertThat(jar.get("jarId").asString()).isNotEqualTo(aliceNec.toString());
        }
    }

    @Test
    void idempotencyKeys_areNotSharedBetweenUsers() {
        TestUser alice = newTestUser();
        TestUser bob = newTestUser();
        String key = newKey();
        UUID aliceNec = jarId(alice.token(), "NEC");
        UUID bobNec = jarId(bob.token(), "NEC");

        Resp a = post(alice.token(), ALLOCATIONS, key, allocation("1000", split(aliceNec, "1000")));
        Resp b = post(bob.token(), ALLOCATIONS, key, allocation("1000", split(bobNec, "1000")));

        assertThat(a.status()).isEqualTo(201);
        assertThat(b.status()).isEqualTo(201);
        assertThat(b.header("Idempotency-Replayed")).isEqualTo("false");
        assertThat(b.json().get("decisionId")).isNotEqualTo(a.json().get("decisionId"));
        assertThat(balanceOf(bobNec)).isEqualByComparingTo("1000");
    }

    @Test
    void everyBudgetEndpoint_needsAToken() {
        assertThat(post(null, SUGGESTIONS, null, amountBody("1000")).status()).isEqualTo(401);
        assertThat(post(null, ALLOCATIONS, newKey(), allocation("1000", split(UUID.randomUUID(), "1000")))
                        .status())
                .isEqualTo(401);
        assertThat(get(null, "/api/v1/budget/periods/" + thisMonth()).status()).isEqualTo(401);
    }

    // -------------------------------------------------------- planned vs spent

    @Test
    void periods_ofAnEmptyMonth_listEveryActiveJarWithZeros() {
        TestUser user = newTestUser();

        JsonNode period = get(user.token(), "/api/v1/budget/periods/2020-01").json();

        assertThat(period.get("month").asString()).isEqualTo("2020-01");
        assertThat(period.get("closed").asBoolean()).isFalse();
        assertThat(moneyOf(period.get("planned"))).isEqualTo("0.00");
        assertThat(moneyOf(period.get("spent"))).isEqualTo("0.00");
        assertThat(period.get("jars")).hasSize(6);
        for (JsonNode jar : period.get("jars")) {
            assertThat(moneyOf(jar.get("remaining"))).isEqualTo("0.00");
            assertThat(jar.get("closedAt").isNull()).isTrue();
        }
    }

    @Test
    void spending_isCountedAgainstThePlan_andFollowsAnExpenseThatMovesToAnotherJar() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID play = jarId(user.token(), "PLAY");
        post(user.token(), ALLOCATIONS, newKey(), allocation("2000000", split(nec, "1500000"), split(play, "500000")));
        String txId = post(
                        user.token(), "/api/v1/transactions", newKey(), expenseBody(nec, "120000", CATEGORY_FOOD, null))
                .json()
                .get("transaction")
                .get("id")
                .asString();
        post(user.token(), "/api/v1/transactions", newKey(), expenseBody(play, "30000", CATEGORY_FOOD, null));

        JsonNode period = awaitPeriod(user, thisMonth(), p -> "150000.00".equals(moneyOf(p.get("spent"))));

        assertThat(moneyOf(period.get("planned"))).isEqualTo("2000000.00");
        JsonNode necLine = jarLine(period.get("jars"), nec);
        assertThat(moneyOf(necLine.get("planned"))).isEqualTo("1500000.00");
        assertThat(moneyOf(necLine.get("spent"))).isEqualTo("120000.00");
        assertThat(moneyOf(necLine.get("remaining"))).isEqualTo("1380000.00");

        // moving the expense to another jar moves the spending with it
        assertThat(patch(user.token(), "/api/v1/transactions/" + txId, "{\"jarId\":\"" + play + "\"}")
                        .status())
                .isEqualTo(200);

        JsonNode moved = awaitPeriod(
                user,
                thisMonth(),
                p -> "0.00".equals(moneyOf(jarLine(p.get("jars"), nec).get("spent"))));
        assertThat(moneyOf(jarLine(moved.get("jars"), play).get("spent"))).isEqualTo("150000.00");
        assertThat(moneyOf(moved.get("spent"))).as("the total does not change").isEqualTo("150000.00");
    }

    @Test
    void spending_inThePreviousMonth_isCountedInThatMonthOnly() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        YearMonth lastMonth = thisMonth().minusMonths(1);

        post(
                user.token(),
                "/api/v1/transactions",
                newKey(),
                expenseBody(nec, "77000", CATEGORY_FOOD, atMiddayOf(lastMonth)));

        awaitPeriod(user, lastMonth, p -> "77000.00".equals(moneyOf(p.get("spent"))));
        assertThat(moneyOf(get(user.token(), "/api/v1/budget/periods/" + thisMonth())
                        .json()
                        .get("spent")))
                .isEqualTo("0.00");
    }

    @Test
    void incomeAndJarTransfers_areNotSpending() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        post(user.token(), "/api/v1/transactions", newKey(), incomeBody(nec, "1000000"));
        post(user.token(), "/api/v1/jars/transfers", newKey(), transferBody(nec, edu, "400000"));
        // a marker expense, processed after the other two events of this user
        post(user.token(), "/api/v1/transactions", newKey(), expenseBody(nec, "1000", CATEGORY_FOOD, null));

        JsonNode period = awaitPeriod(user, thisMonth(), p -> "1000.00".equals(moneyOf(p.get("spent"))));

        assertThat(moneyOf(jarLine(period.get("jars"), edu).get("spent"))).isEqualTo("0.00");
        assertThat(moneyOf(period.get("spent"))).isEqualTo("1000.00");
    }

    private JsonNode awaitPeriod(TestUser user, YearMonth month, java.util.function.Predicate<JsonNode> ready) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            last[0] = get(user.token(), "/api/v1/budget/periods/" + month).json();
            return ready.test(last[0]);
        });
        return last[0];
    }
}
