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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The read-only views the goals module (and later the advisor) use: monthly cashflow and open anomalies. */
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
}
