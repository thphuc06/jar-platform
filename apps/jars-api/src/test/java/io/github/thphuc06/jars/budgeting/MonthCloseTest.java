package io.github.thphuc06.jars.budgeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.thphuc06.jars.UserApiTestSupport;
import io.github.thphuc06.jars.budgeting.MonthClosed.JarPeriodSummary;
import io.github.thphuc06.jars.budgeting.internal.service.MonthCloseJob;
import io.github.thphuc06.jars.budgeting.internal.service.MonthCloseService;
import io.github.thphuc06.jars.budgeting.internal.service.MonthCloseService.CloseReport;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.JsonNode;

/**
 * Flow F6 against a real database: closing a month, closing it again, closing everyone, and the notification that
 * follows. The service is called directly; nothing here needs AWS.
 */
@RecordApplicationEvents
class MonthCloseTest extends UserApiTestSupport {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    MonthCloseService months;

    @Autowired
    MonthCloseJob job;

    @Autowired
    ApplicationEvents events;

    // ---------------------------------------------------------------- helpers

    private static YearMonth lastMonth() {
        return YearMonth.now(VIETNAM).minusMonths(1);
    }

    private static String middayOf(YearMonth month) {
        return month.atDay(15).atTime(12, 0).atZone(VIETNAM).toInstant().toString();
    }

    private static String money(JsonNode node) {
        return node.get("amount").asString();
    }

    /** A user who planned 10,000,000 in {@code month} (NEC 6,000,000 and EDU 4,000,000) and spent 4,350,000 of it. */
    private TestUser userWithActivityIn(YearMonth month) {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        String at = middayOf(month);
        assertThat(post(
                                user.token(),
                                "/api/v1/budget/allocations",
                                newKey(),
                                "{\"amount\":10000000,\"occurredAt\":\"" + at + "\",\"splits\":[{\"jarId\":\"" + nec
                                        + "\",\"amount\":6000000},{\"jarId\":\"" + edu + "\",\"amount\":4000000}]}")
                        .status())
                .isEqualTo(201);
        spend(user, nec, "150000", at);
        spend(user, edu, "4200000", at); // more than the 4,000,000 planned for EDU
        awaitPeriod(user, month, p -> "4350000.00".equals(money(p.get("spent"))));
        return user;
    }

    private void spend(TestUser user, UUID jar, String amount, String at) {
        assertThat(post(user.token(), "/api/v1/transactions", newKey(), expenseBody(jar, amount, CATEGORY_FOOD, at))
                        .status())
                .isEqualTo(201);
    }

    private JsonNode awaitPeriod(TestUser user, YearMonth month, Predicate<JsonNode> ready) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> {
            last[0] = get(user.token(), "/api/v1/budget/periods/" + month).json();
            return ready.test(last[0]);
        });
        return last[0];
    }

    private List<MonthClosed> closedEventsOf(TestUser user) {
        return events.stream(MonthClosed.class)
                .filter(e -> e.userId().equals(user.id()))
                .toList();
    }

    private JsonNode notifications(TestUser user) {
        return get(user.token(), "/api/v1/notifications?limit=100").json();
    }

    /** Waits until nothing is left to deliver for this event (every listener of it has finished). */
    private void awaitDelivered(MonthClosed event) {
        await().atMost(WAIT)
                .pollInterval(Duration.ofMillis(50))
                .until(() -> jdbc.sql("SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?")
                                .param("%" + event.eventId() + "%")
                                .query(Integer.class)
                                .single()
                        == 0);
    }

    private record Snapshot(String rows, int markers, int events) {}

    private Snapshot snapshot(TestUser user, YearMonth month) {
        String rows = jdbc.sql("""
                        SELECT string_agg(jar_id || '|' || period_month || '|' || planned_amount || '|' || spent_amount
                                          || '|' || COALESCE(closed_at::text, '-'), ';' ORDER BY jar_id, period_month)
                        FROM jar_periods WHERE user_id = ? AND period_month IN (?, ?)
                        """)
                .params(user.id().value(), month.atDay(1), month.plusMonths(1).atDay(1))
                .query(String.class)
                .optional()
                .orElse("");
        int markers = jdbc.sql("SELECT count(*) FROM processed_messages WHERE message_id = ?")
                .param(user.id() + ":" + month)
                .query(Integer.class)
                .single();
        return new Snapshot(rows, markers, closedEventsOf(user).size());
    }

    // ---------------------------------------------------------------- one user

    @Test
    void closingAMonth_closesEveryActiveJar_startsTheNextMonth_andAnnouncesTheSummary() {
        YearMonth month = lastMonth();
        TestUser user = userWithActivityIn(month);

        assertThat(months.closeMonth(user.id(), month)).isTrue();

        JsonNode closed = get(user.token(), "/api/v1/budget/periods/" + month).json();
        assertThat(closed.get("closed").asBoolean()).isTrue();
        assertThat(closed.get("jars")).hasSize(6);
        closed.get("jars").forEach(j -> assertThat(j.get("closedAt").isNull()).isFalse());
        assertThat(money(closed.get("planned"))).isEqualTo("10000000.00");
        assertThat(money(closed.get("spent"))).isEqualTo("4350000.00");

        // every active jar now has a fresh, open record for the running month
        JsonNode running = get(user.token(), "/api/v1/budget/periods/" + month.plusMonths(1))
                .json();
        assertThat(running.get("closed").asBoolean()).isFalse();
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM jar_periods WHERE user_id = ? AND period_month = ? AND closed_at IS NULL")
                        .params(user.id().value(), month.plusMonths(1).atDay(1))
                        .query(Integer.class)
                        .single())
                .isEqualTo(6);

        List<MonthClosed> published = closedEventsOf(user);
        assertThat(published).hasSize(1);
        MonthClosed event = published.getFirst();
        assertThat(event.month()).isEqualTo(month);
        assertThat(event.summary()).hasSize(6);
        assertThat(event.totalPlanned().amount()).isEqualByComparingTo("10000000");
        assertThat(event.totalSpent().amount()).isEqualByComparingTo("4350000");
        JarPeriodSummary edu = event.summary().stream()
                .filter(j -> j.jarName().equals("Giáo dục"))
                .findFirst()
                .orElseThrow();
        assertThat(edu.planned().amount()).isEqualByComparingTo("4000000");
        assertThat(edu.spent().amount()).isEqualByComparingTo("4200000");
    }

    @Test
    void closingAMonthTwice_changesNothing() {
        YearMonth month = lastMonth();
        TestUser user = userWithActivityIn(month);
        assertThat(months.closeMonth(user.id(), month)).isTrue();
        Snapshot once = snapshot(user, month);

        assertThat(months.closeMonth(user.id(), month))
                .as("nothing left to close")
                .isFalse();
        assertThat(months.closeMonth(user.id(), month)).isFalse();

        assertThat(snapshot(user, month)).isEqualTo(once);
        assertThat(once.events()).isEqualTo(1);
        assertThat(once.markers()).isEqualTo(1);
    }

    @Test
    void closingAMonth_notifiesTheUser_onceAndWithTheNumbers() {
        YearMonth month = lastMonth();
        TestUser user = userWithActivityIn(month);

        months.closeMonth(user.id(), month);
        months.closeMonth(user.id(), month);

        await().atMost(WAIT)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> notifications(user).get("items").size() >= 1);
        awaitDelivered(closedEventsOf(user).getFirst());
        JsonNode list = notifications(user);
        assertThat(list.get("items")).as("closing twice notifies once").hasSize(1);
        JsonNode n = list.get("items").get(0);
        assertThat(n.get("type").asString()).isEqualTo("MONTH_CLOSED");
        assertThat(n.get("title").asString())
                .isEqualTo(String.format("Đã khép sổ tháng %02d/%d", month.getMonthValue(), month.getYear()));
        assertThat(n.get("body").asString())
                .contains("4.350.000")
                .contains("10.000.000")
                .contains("Giáo dục")
                .contains("vượt kế hoạch");
        assertThat(n.get("severity").asString()).as("EDU overspent").isEqualTo("MEDIUM");
        assertThat(n.get("readAt").isNull()).isTrue();
    }

    @Test
    void aMonthWithoutAnyMovement_isClosedWithoutBotheringTheUser() {
        TestUser user = newTestUser();
        YearMonth month = lastMonth();

        assertThat(months.closeMonth(user.id(), month)).isTrue();

        List<MonthClosed> published = closedEventsOf(user);
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().totalPlanned().isZero()).isTrue();
        assertThat(published.getFirst().totalSpent().isZero()).isTrue();
        awaitDelivered(published.getFirst());
        assertThat(notifications(user).get("items")).isEmpty();
        assertThat(get(user.token(), "/api/v1/budget/periods/" + month)
                        .json()
                        .get("closed")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void spendingThatArrivesAfterTheClose_isStillCounted_butTheMonthStaysClosedOnce() {
        YearMonth month = lastMonth();
        TestUser user = userWithActivityIn(month);
        months.closeMonth(user.id(), month);
        String closedAtBefore = jdbc.sql(
                        "SELECT string_agg(closed_at::text, ',' ORDER BY jar_id) FROM jar_periods WHERE user_id = ? AND period_month = ?")
                .params(user.id().value(), month.atDay(1))
                .query(String.class)
                .single();

        spend(user, jarId(user.token(), "NEC"), "50000", middayOf(month));

        awaitPeriod(user, month, p -> "4400000.00".equals(money(p.get("spent"))));
        assertThat(jdbc.sql(
                                "SELECT string_agg(closed_at::text, ',' ORDER BY jar_id) FROM jar_periods WHERE user_id = ? AND period_month = ?")
                        .params(user.id().value(), month.atDay(1))
                        .query(String.class)
                        .single())
                .as("closed_at never moves")
                .isEqualTo(closedAtBefore);
        assertThat(closedEventsOf(user)).as("a month is announced once").hasSize(1);
    }

    @Test
    void aJarArchivedDuringTheMonth_stillAppearsInTheSummary() {
        YearMonth month = lastMonth();
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        JsonNode custom = post(user.token(), "/api/v1/jars", null, "{\"name\":\"Quỹ tạm\"}")
                .json();
        UUID temp = UUID.fromString(custom.get("id").asString());
        String at = middayOf(month);
        post(
                user.token(),
                "/api/v1/budget/allocations",
                newKey(),
                "{\"amount\":2000,\"occurredAt\":\"" + at + "\",\"splits\":[{\"jarId\":\"" + nec
                        + "\",\"amount\":1000},{\"jarId\":\"" + temp + "\",\"amount\":1000}]}");
        spend(user, temp, "1000", at); // empties the jar, so it can be archived
        awaitPeriod(user, month, p -> "1000.00".equals(money(p.get("spent"))));
        assertThat(delete(user.token(), "/api/v1/jars/" + temp).status()).isEqualTo(204);

        months.closeMonth(user.id(), month);

        MonthClosed event = closedEventsOf(user).getFirst();
        JarPeriodSummary archived = event.summary().stream()
                .filter(j -> j.jarId().equals(temp))
                .findFirst()
                .orElseThrow();
        assertThat(archived.jarName()).isEqualTo("Hũ đã lưu trữ");
        assertThat(archived.planned().amount()).isEqualByComparingTo("1000");
        assertThat(archived.spent().amount()).isEqualByComparingTo("1000");
        assertThat(event.summary()).as("six active jars plus the archived one").hasSize(7);
    }

    @Test
    void theRunningMonth_cannotBeClosed() {
        TestUser user = newTestUser();

        assertThatThrownBy(() -> months.closeMonth(user.id(), YearMonth.now(VIETNAM)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        months.closeMonth(user.id(), YearMonth.now(VIETNAM).plusMonths(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(closedEventsOf(user)).isEmpty();
    }

    // ---------------------------------------------------------------- everyone

    @Test
    void closingEveryone_twice_isTheSameAsOnce() {
        YearMonth month = YearMonth.of(2019, 3);
        TestUser user = userWithActivityIn(month);

        CloseReport first = months.closeAll(month);
        Snapshot afterFirst = snapshot(user, month);
        CloseReport second = months.closeAll(month);

        assertThat(first.alreadyDone()).isFalse();
        assertThat(first.failed()).isZero();
        assertThat(first.users()).isGreaterThanOrEqualTo(1);
        assertThat(first.closed()).isEqualTo(first.users());
        assertThat(second.alreadyDone())
                .as("the batch marker short-cuts the rerun")
                .isTrue();
        assertThat(second.users()).isZero();
        assertThat(snapshot(user, month)).isEqualTo(afterFirst);
        assertThat(afterFirst.events()).isEqualTo(1);
        assertThat(get(user.token(), "/api/v1/budget/periods/" + month)
                        .json()
                        .get("closed")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void ifTheBatchMarkerIsLost_rerunningStillChangesNothing() {
        YearMonth month = YearMonth.of(2019, 4);
        TestUser user = userWithActivityIn(month);
        CloseReport first = months.closeAll(month);
        Snapshot afterFirst = snapshot(user, month);
        // a run that crashed after closing everybody but before writing the batch marker
        jdbc.sql("DELETE FROM processed_messages WHERE consumer = 'budgeting.month-close.batch' AND message_id = ?")
                .param(month.toString())
                .update();

        CloseReport rerun = months.closeAll(month);

        assertThat(rerun.alreadyDone()).isFalse();
        assertThat(rerun.users()).isEqualTo(first.users());
        assertThat(rerun.closed()).as("every user was closed already").isZero();
        assertThat(rerun.failed()).isZero();
        assertThat(snapshot(user, month)).isEqualTo(afterFirst);
    }

    @Test
    void theJobMessage_closesThePreviousMonth_sameAsCallingTheService() {
        YearMonth month = lastMonth();
        TestUser user = userWithActivityIn(month);

        // the schedule's payload names no month: the job works it out from the clock (Vietnam time)
        CloseReport report = job.handle("{\"job\":\"MONTH_CLOSE\"}");

        assertThat(report.month()).isEqualTo(month);
        assertThat(report.failed()).isZero();
        assertThat(get(user.token(), "/api/v1/budget/periods/" + month)
                        .json()
                        .get("closed")
                        .asBoolean())
                .isTrue();
        assertThat(closedEventsOf(user)).hasSize(1);

        CloseReport again = job.handle("{\"job\":\"MONTH_CLOSE\"}");
        assertThat(again.alreadyDone()).isTrue();
        assertThat(closedEventsOf(user)).hasSize(1);
    }
}
