package io.github.thphuc06.jars.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.thphuc06.jars.UserApiTestSupport;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The read-only views the goals module and the advisor use: cashflow, open anomalies, spending behaviour. */
class InsightsApiTest extends UserApiTestSupport {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    InsightsApi insights;

    private void setCashflow(UserId user, YearMonth month, String income, String expense) {
        jdbc.sql("INSERT INTO monthly_cashflow (user_id, month, income, expense) VALUES (?, ?, ?, ?)")
                .params(user.value(), month.atDay(1), new BigDecimal(income), new BigDecimal(expense))
                .update();
    }

    private void addAnomaly(UserId user, String status) {
        jdbc.sql("""
                        INSERT INTO anomalies (id, user_id, jar_id, transaction_id, detector, direction, severity,
                                               score, threshold, amount, status, detected_at)
                        VALUES (?, ?, ?, ?, 'MAD', 'UP', 'HIGH', 9, 3.5, 100000, ?, ?)
                        """)
                .params(
                        Uuids.v7(),
                        user.value(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        status,
                        java.time.OffsetDateTime.ofInstant(Instant.now(), java.time.ZoneOffset.UTC))
                .update();
    }

    @Test
    void monthlyCashflow_returnsTheWindowOldestFirst_andSkipsMonthsWithoutActivity() {
        UserId user = newTestUser().id();
        YearMonth now = YearMonth.now(VIETNAM);
        setCashflow(user, now.minusMonths(1), "20000000", "12000000");
        setCashflow(user, now.minusMonths(2), "18000000", "19000000");
        setCashflow(user, now.minusMonths(3), "0", "0"); // a row without movement counts as no activity
        setCashflow(user, now.minusMonths(9), "5000000", "1000000"); // outside a 6 month window

        List<MonthlyCashflow> flows = insights.monthlyCashflow(user, 6);

        assertThat(flows).extracting(MonthlyCashflow::month).containsExactly(now.minusMonths(2), now.minusMonths(1));
        MonthlyCashflow last = flows.getLast();
        assertThat(last.income()).isEqualTo(Money.vnd("20000000"));
        assertThat(last.expense()).isEqualTo(Money.vnd("12000000"));
        assertThat(last.net()).isEqualTo(Money.vnd("8000000"));
        assertThat(flows.getFirst().net()).as("a month can be negative").isEqualTo(Money.vnd("-1000000"));
        assertThat(insights.monthlyCashflow(user, 12)).hasSize(3);
        assertThat(insights.monthlyCashflow(user, 1))
                .as("only the current month")
                .isEmpty();
    }

    @Test
    void monthlyCashflow_followsRealPostingsIncludingTheCurrentMonth() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        post(user.token(), "/api/v1/transactions", newKey(), incomeBody(nec, "5000000"));
        post(user.token(), "/api/v1/transactions", newKey(), expenseBody(nec, "200000", CATEGORY_FOOD, null));

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(insights.monthlyCashflow(user.id(), 3))
                        .singleElement()
                        .satisfies(m -> {
                            assertThat(m.month()).isEqualTo(YearMonth.now(VIETNAM));
                            assertThat(m.income()).isEqualTo(Money.vnd("5000000"));
                            assertThat(m.expense()).isEqualTo(Money.vnd("200000"));
                        }));
    }

    @Test
    void monthlyCashflow_neverShowsAnotherUsersMonths() {
        UserId alice = newTestUser().id();
        UserId bob = newTestUser().id();
        setCashflow(alice, YearMonth.now(VIETNAM).minusMonths(1), "1000", "500");

        assertThat(insights.monthlyCashflow(bob, 6)).isEmpty();
        assertThat(insights.monthlyCashflow(alice, 6)).hasSize(1);
    }

    @Test
    void monthlyCashflow_rejectsAWindowOutsideTheAllowedRange() {
        UserId user = newTestUser().id();

        assertThatThrownBy(() -> insights.monthlyCashflow(user, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> insights.monthlyCashflow(user, InsightsApi.MAX_MONTHS + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(insights.monthlyCashflow(user, InsightsApi.MAX_MONTHS)).isEmpty();
    }

    @Test
    void openAnomalyCount_countsOnlyOpenOnesOfTheUser() {
        UserId user = newTestUser().id();
        UserId other = newTestUser().id();
        addAnomaly(user, "OPEN");
        addAnomaly(user, "OPEN");
        addAnomaly(user, "CONFIRMED");
        addAnomaly(other, "OPEN");

        assertThat(insights.openAnomalyCount(user)).isEqualTo(2);
        assertThat(insights.openAnomalyCount(other)).isEqualTo(1);
        assertThat(insights.openAnomalyCount(newTestUser().id())).isZero();
    }

    // --- openAnomalies ---

    private void addAnomaly(
            UserId user,
            UUID jar,
            String detector,
            String direction,
            String severity,
            String amount,
            String status,
            Instant at) {
        jdbc.sql("""
                        INSERT INTO anomalies (id, user_id, jar_id, transaction_id, detector, direction, severity,
                                               score, threshold, amount, status, detected_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 9, 3.5, ?, ?, ?)
                        """)
                .params(
                        Uuids.v7(),
                        user.value(),
                        jar,
                        UUID.randomUUID(),
                        detector,
                        direction,
                        severity,
                        new BigDecimal(amount),
                        status,
                        java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC))
                .update();
    }

    private void setDetectorMean(UserId user, UUID jar, double mean) {
        jdbc.sql("""
                        INSERT INTO anomaly_detector_state (jar_id, user_id, sample_count, mean, updated_at)
                        VALUES (?, ?, 12, ?, now())
                        ON CONFLICT (jar_id) DO UPDATE SET sample_count = 12, mean = EXCLUDED.mean
                        """).params(jar, user.value(), mean).update();
    }

    @Test
    void openAnomalies_listsOpenOnesNewestFirst_withKindJarAndUsualLevel() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID play = jarId(user.token(), "PLAY");
        Instant now = Instant.now();
        addAnomaly(user.id(), nec, "MAD", "UP", "HIGH", "1800000", "OPEN", now.minusSeconds(300));
        addAnomaly(user.id(), play, "CUSUM", "UP", "MEDIUM", "250000", "OPEN", now.minusSeconds(100));
        addAnomaly(user.id(), play, "CUSUM", "DOWN", "LOW", "20000", "OPEN", now.minusSeconds(500));
        addAnomaly(user.id(), nec, "MAD", "UP", "HIGH", "9000000", "CONFIRMED", now); // already answered
        setDetectorMean(user.id(), nec, 149_999.6);

        List<OpenAnomaly> open = insights.openAnomalies(user.id(), 10);

        assertThat(open)
                .extracting(OpenAnomaly::kind)
                .containsExactly(OpenAnomaly.Kind.DRIFT_UP, OpenAnomaly.Kind.SPIKE, OpenAnomaly.Kind.DRIFT_DOWN);
        OpenAnomaly spike = open.get(1);
        assertThat(spike.severity()).isEqualTo("HIGH");
        assertThat(spike.amount()).isEqualTo(Money.vnd("1800000"));
        assertThat(spike.usualAmount()).as("the detector's mean, whole VND").isEqualTo(Money.vnd("150000"));
        assertThat(spike.detectedOn()).isEqualTo(LocalDate.now(VIETNAM));
        assertThat(spike.jarName())
                .isEqualTo(jdbc.sql("SELECT name FROM jars WHERE id = ?")
                        .param(nec)
                        .query(String.class)
                        .single());
        assertThat(open.getFirst().usualAmount())
                .as("a jar without a baseline has none")
                .isNull();
    }

    @Test
    void openAnomalies_isCappedByTheLimit_andTheCountStaysTheTotal() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        for (int i = 0; i < 4; i++) {
            addAnomaly(
                    user.id(),
                    nec,
                    "MAD",
                    "UP",
                    "MEDIUM",
                    "100000",
                    "OPEN",
                    Instant.now().minusSeconds(i * 10L));
        }

        assertThat(insights.openAnomalies(user.id(), 2)).hasSize(2);
        assertThat(insights.openAnomalyCount(user.id())).isEqualTo(4);
        assertThatThrownBy(() -> insights.openAnomalies(user.id(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openAnomalies_neverShowsAnotherUsersAlerts() {
        TestUser alice = newTestUser();
        TestUser bob = newTestUser();
        addAnomaly(alice.id(), jarId(alice.token(), "NEC"), "MAD", "UP", "HIGH", "1000000", "OPEN", Instant.now());

        assertThat(insights.openAnomalies(bob.id(), 5)).isEmpty();
        assertThat(insights.openAnomalies(alice.id(), 5)).hasSize(1);
    }

    // --- spendingBehaviour ---

    private void setCategorySpend(UserId user, UUID category, YearMonth month, String spent) {
        jdbc.sql("""
                        INSERT INTO monthly_category_spend (user_id, category_id, month, spent, txn_count)
                        VALUES (?, ?, ?, ?, 1)
                        """)
                .params(user.value(), category, month.atDay(1), new BigDecimal(spent))
                .update();
    }

    @Test
    void spendingBehaviour_comparesThisMonthWithTheAverageOfThePrecedingMonths() {
        UserId user = newTestUser().id();
        YearMonth now = YearMonth.now(VIETNAM);
        setCategorySpend(user, CATEGORY_FOOD, now, "1800000");
        setCategorySpend(user, CATEGORY_FOOD, now.minusMonths(1), "1000000");
        setCategorySpend(user, CATEGORY_FOOD, now.minusMonths(2), "1500000");
        setCategorySpend(user, CATEGORY_FOOD, now.minusMonths(3), "1100000");
        setCategorySpend(user, CATEGORY_TRANSPORT, now, "300000");
        setCategorySpend(user, CATEGORY_TRANSPORT, now.minusMonths(1), "600000");

        SpendingBehaviour b = insights.spendingBehaviour(user);

        assertThat(b.month()).isEqualTo(now);
        assertThat(b.baselineMonths()).isEqualTo(3);
        assertThat(b.comparable()).isTrue();
        assertThat(b.categories())
                .extracting(SpendingBehaviour.CategoryBehaviour::category)
                .containsExactly("Ăn uống", "Di chuyển");
        SpendingBehaviour.CategoryBehaviour food = b.categories().getFirst();
        assertThat(food.spent()).isEqualTo(Money.vnd("1800000"));
        assertThat(food.usual())
                .as("(1.000.000 + 1.500.000 + 1.100.000) / 3, rounded")
                .isEqualTo(Money.vnd("1200000"));
        assertThat(food.difference()).isEqualTo(Money.vnd("600000"));
        assertThat(food.changePercent()).isEqualTo(50);
        SpendingBehaviour.CategoryBehaviour transport = b.categories().get(1);
        assertThat(transport.usual())
                .as("a month with other spending but none here counts as zero: (600.000 + 0 + 0) / 3")
                .isEqualTo(Money.vnd("200000"));
        assertThat(transport.changePercent()).isEqualTo(50);
    }

    @Test
    void spendingBehaviour_reportsHowFarThroughTheMonthWeAre() {
        UserId user = newTestUser().id();
        YearMonth now = YearMonth.now(VIETNAM);
        setCategorySpend(user, CATEGORY_FOOD, now, "100000");

        SpendingBehaviour b = insights.spendingBehaviour(user);

        assertThat(b.dayOfMonth()).isEqualTo(LocalDate.now(VIETNAM).getDayOfMonth());
        assertThat(b.daysInMonth()).isEqualTo(now.lengthOfMonth());
        assertThat(b.categories().getFirst().spent())
                .as("this month's figure is as recorded, not scaled up to a whole month")
                .isEqualTo(Money.vnd("100000"));
    }

    @Test
    void spendingBehaviour_aCategoryBelowItsUsualLevel_hasANegativeDifferenceAndPercentage() {
        UserId user = newTestUser().id();
        YearMonth now = YearMonth.now(VIETNAM);
        setCategorySpend(user, CATEGORY_FOOD, now, "900000");
        setCategorySpend(user, CATEGORY_FOOD, now.minusMonths(1), "1200000");
        setCategorySpend(user, CATEGORY_FOOD, now.minusMonths(2), "1200000");
        setCategorySpend(user, CATEGORY_TRANSPORT, now, "1000"); // far below: -99.8%
        setCategorySpend(user, CATEGORY_TRANSPORT, now.minusMonths(1), "500000");
        setCategorySpend(user, CATEGORY_TRANSPORT, now.minusMonths(2), "500000");

        SpendingBehaviour b = insights.spendingBehaviour(user);

        SpendingBehaviour.CategoryBehaviour food = b.categories().getFirst();
        assertThat(food.spent()).isEqualTo(Money.vnd("900000"));
        assertThat(food.usual()).isEqualTo(Money.vnd("1200000"));
        assertThat(food.difference()).isEqualTo(Money.vnd("-300000"));
        assertThat(food.changePercent()).isEqualTo(-25);
        assertThat(b.categories().get(1).changePercent()).isEqualTo(-100); // -99.8 rounds to -100
    }

    @Test
    void spendingBehaviour_withoutAnyHistory_isEmpty() {
        SpendingBehaviour b = insights.spendingBehaviour(newTestUser().id());

        assertThat(b.baselineMonths()).isZero();
        assertThat(b.comparable()).isFalse();
        assertThat(b.categories()).isEmpty();
    }

    @Test
    void spendingBehaviour_withOneEarlierMonthOnly_offersNoUsualLevel() {
        UserId user = newTestUser().id();
        YearMonth now = YearMonth.now(VIETNAM);
        setCategorySpend(user, CATEGORY_FOOD, now, "500000");
        setCategorySpend(user, CATEGORY_FOOD, now.minusMonths(1), "400000");

        SpendingBehaviour b = insights.spendingBehaviour(user);

        assertThat(b.baselineMonths()).isEqualTo(1);
        assertThat(b.comparable()).isFalse();
        assertThat(b.categories()).singleElement().satisfies(c -> {
            assertThat(c.spent()).isEqualTo(Money.vnd("500000"));
            assertThat(c.usual()).isNull();
            assertThat(c.difference()).isNull();
            assertThat(c.changePercent()).isNull();
        });
    }

    @Test
    void spendingBehaviour_aCategoryWithoutHistory_hasZeroUsualAndNoPercentage() {
        UserId user = newTestUser().id();
        YearMonth now = YearMonth.now(VIETNAM);
        setCategorySpend(user, CATEGORY_TRANSPORT, now.minusMonths(1), "600000");
        setCategorySpend(user, CATEGORY_TRANSPORT, now.minusMonths(2), "600000");
        setCategorySpend(user, CATEGORY_FOOD, now, "250000"); // never spent on food before

        SpendingBehaviour b = insights.spendingBehaviour(user);

        assertThat(b.comparable()).isTrue();
        assertThat(b.categories()).singleElement().satisfies(c -> {
            assertThat(c.category()).isEqualTo("Ăn uống");
            assertThat(c.usual()).isEqualTo(Money.vnd("0"));
            assertThat(c.difference()).isEqualTo(Money.vnd("250000"));
            assertThat(c.changePercent()).isNull();
        });
    }

    @Test
    void spendingBehaviour_monthsWithoutAnySpendingAreNotCountedAsZero() {
        UserId user = newTestUser().id();
        YearMonth now = YearMonth.now(VIETNAM);
        setCategorySpend(user, CATEGORY_FOOD, now, "920000");
        setCategorySpend(user, CATEGORY_FOOD, now.minusMonths(1), "600000");
        // nothing two months ago: the app was not in use
        setCategorySpend(user, CATEGORY_FOOD, now.minusMonths(3), "1000000");
        setCategorySpend(user, CATEGORY_FOOD, now.minusMonths(4), "50000000"); // older than the window

        SpendingBehaviour b = insights.spendingBehaviour(user);

        assertThat(b.baselineMonths()).isEqualTo(2);
        assertThat(b.categories().getFirst().usual()).isEqualTo(Money.vnd("800000"));
        assertThat(b.categories().getFirst().changePercent()).isEqualTo(15); // 120.000 / 800.000
    }

    @Test
    void spendingBehaviour_listsAtMostEightCategories_largestFirst_andNamesTheUncategorised() {
        UserId user = newTestUser().id();
        YearMonth now = YearMonth.now(VIETNAM);
        setCategorySpend(user, new UUID(0L, 0L), now, "999000000");
        for (int i = 1; i <= 9; i++) {
            setCategorySpend(user, UUID.randomUUID(), now, i + "000");
        }

        SpendingBehaviour b = insights.spendingBehaviour(user);

        assertThat(b.categories()).hasSize(InsightsApi.MAX_CATEGORIES);
        assertThat(b.categories().getFirst().category()).isEqualTo("Chưa phân loại");
        assertThat(b.categories().get(1).category()).isEqualTo("Danh mục khác");
        assertThat(b.categories().getLast().spent()).as("the smallest is cut").isEqualTo(Money.vnd("3000"));
    }

    @Test
    void spendingBehaviour_neverMixesInAnotherUsersSpending() {
        UserId alice = newTestUser().id();
        UserId bob = newTestUser().id();
        YearMonth now = YearMonth.now(VIETNAM);
        setCategorySpend(alice, CATEGORY_FOOD, now.minusMonths(1), "700000");
        setCategorySpend(alice, CATEGORY_FOOD, now.minusMonths(2), "700000");
        setCategorySpend(bob, CATEGORY_FOOD, now, "10000");

        SpendingBehaviour b = insights.spendingBehaviour(bob);

        assertThat(b.baselineMonths()).isZero();
        assertThat(b.categories())
                .singleElement()
                .satisfies(c -> assertThat(c.spent()).isEqualTo(Money.vnd("10000")));
    }
}
