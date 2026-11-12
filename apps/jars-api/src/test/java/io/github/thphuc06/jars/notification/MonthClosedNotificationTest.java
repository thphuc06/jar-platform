package io.github.thphuc06.jars.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.thphuc06.jars.UserApiTestSupport;
import io.github.thphuc06.jars.budgeting.MonthClosed;
import io.github.thphuc06.jars.budgeting.MonthClosed.JarPeriodSummary;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.Uuids;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** What the notification module makes of {@link MonthClosed}, driven with the event itself (no budgeting involved). */
class MonthClosedNotificationTest extends UserApiTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    private static JarPeriodSummary jar(String name, String planned, String spent) {
        return new JarPeriodSummary(UUID.randomUUID(), name, Money.vnd(planned), Money.vnd(spent));
    }

    private MonthClosed closed(TestUser user, YearMonth month, JarPeriodSummary... summary) {
        return new MonthClosed(Uuids.v7(), user.id(), month, List.of(summary));
    }

    private void publish(MonthClosed... events) {
        tx.executeWithoutResult(s -> {
            for (MonthClosed event : events) {
                publisher.publishEvent(event);
            }
        });
        for (MonthClosed event : events) {
            await().atMost(WAIT)
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> jdbc.sql("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?")
                                    .param("%" + event.eventId() + "%")
                                    .query(Integer.class)
                                    .single()
                            == 0);
        }
    }

    private JsonNode items(TestUser user) {
        return get(user.token(), "/api/v1/notifications?limit=100").json().get("items");
    }

    @Test
    void aClosedMonth_becomesANotificationWithTheTotals() {
        TestUser user = newTestUser();

        publish(closed(
                user, YearMonth.of(2026, 10), jar("Thiết yếu", "6000000", "5000000"), jar("Hưởng thụ", "0", "0")));

        JsonNode n = items(user).get(0);
        assertThat(items(user)).hasSize(1);
        assertThat(n.get("type").asString()).isEqualTo("MONTH_CLOSED");
        assertThat(n.get("title").asString()).isEqualTo("Đã khép sổ tháng 10/2026");
        assertThat(n.get("body").asString()).isEqualTo("Bạn đã chi 5.000.000 ₫ trên kế hoạch 6.000.000 ₫ trong tháng.");
        assertThat(n.get("severity").asString()).isEqualTo("LOW");
        assertThat(n.get("refType").asString()).isEqualTo("BUDGET_MONTH");
        assertThat(n.get("refId").isNull()).isTrue();
    }

    @Test
    void overspentJars_areNamed_andRaiseTheSeverity() {
        TestUser user = newTestUser();

        publish(closed(
                user,
                YearMonth.of(2026, 1),
                jar("Hưởng thụ", "1000000", "1500000"),
                jar("Giáo dục", "500000", "600000"),
                jar("Thiết yếu", "5000000", "4000000")));

        JsonNode n = items(user).get(0);
        assertThat(n.get("title").asString()).isEqualTo("Đã khép sổ tháng 01/2026");
        assertThat(n.get("body").asString()).contains("Hũ Hưởng thụ, Giáo dục đã chi vượt kế hoạch.");
        assertThat(n.get("severity").asString()).isEqualTo("MEDIUM");
    }

    @Test
    void withoutAPlan_onlyTheSpendingIsReported_andSpendingWithoutAPlanIsNotOverspending() {
        TestUser user = newTestUser();

        publish(closed(user, YearMonth.of(2026, 10), jar("Thiết yếu", "0", "250000")));

        JsonNode n = items(user).get(0);
        assertThat(n.get("body").asString()).isEqualTo("Bạn đã chi 250.000 ₫ trong tháng.");
        assertThat(n.get("severity").asString()).isEqualTo("LOW");
    }

    @Test
    void aMonthWithNothingPlannedOrSpent_isNotWorthANotification() {
        TestUser user = newTestUser();

        publish(closed(user, YearMonth.of(2026, 10), jar("Thiết yếu", "0", "0"), jar("Giáo dục", "0", "0")));
        publish(closed(user, YearMonth.of(2026, 11))); // not even a jar

        assertThat(items(user)).isEmpty();
    }

    @Test
    void aRedeliveredEvent_createsOneNotification() {
        TestUser user = newTestUser();
        MonthClosed event = closed(user, YearMonth.of(2026, 10), jar("Thiết yếu", "100", "50"));

        publish(event);
        publish(event); // delivered again after the first delivery finished, as a real redelivery would be

        assertThat(items(user)).hasSize(1);
    }

    @Test
    void notifications_areForTheUserOfTheEventOnly() {
        TestUser alice = newTestUser();
        TestUser bob = newTestUser();

        publish(closed(alice, YearMonth.of(2026, 10), jar("Thiết yếu", "100", "50")));

        assertThat(items(alice)).hasSize(1);
        assertThat(items(bob)).isEmpty();
        assertThat(get(bob.token(), "/api/v1/notifications")
                        .json()
                        .get("unreadCount")
                        .asInt())
                .isZero();
        assertThat(get(alice.token(), "/api/v1/notifications")
                        .json()
                        .get("unreadCount")
                        .asInt())
                .isEqualTo(1);
    }
}
