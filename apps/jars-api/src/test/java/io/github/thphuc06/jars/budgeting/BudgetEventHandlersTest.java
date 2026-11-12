package io.github.thphuc06.jars.budgeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.thphuc06.jars.ApiTestSupport;
import io.github.thphuc06.jars.ledger.TransactionPosted;
import io.github.thphuc06.jars.ledger.TransactionRecategorized;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The listeners that keep {@code jar_periods.spent_amount} up to date, driven with ledger events directly (no HTTP, no
 * ledger rows): what matters here is how they treat each event type and a redelivered event. The listeners are
 * asynchronous, so the tests wait until the event registry holds no unfinished delivery of the events they published,
 * instead of sleeping.
 */
class BudgetEventHandlersTest extends ApiTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    private static TransactionPosted posted(UserId user, UUID jar, String type, String amount, Instant at) {
        return new TransactionPosted(
                Uuids.v7(), UUID.randomUUID(), user, type, Money.vnd(amount), jar, null, at, "MANUAL");
    }

    private static TransactionRecategorized recategorized(
            UserId user, String type, String amount, Instant at, UUID oldJar, UUID newJar, UUID oldCat, UUID newCat) {
        return new TransactionRecategorized(
                Uuids.v7(), UUID.randomUUID(), user, type, Money.vnd(amount), at, oldCat, newCat, oldJar, newJar);
    }

    /**
     * Publishes the events in one transaction (so they are delivered in parallel) and waits until the budgeting
     * listener has finished all of them. Only budgeting's own deliveries are awaited: other modules listen to the same
     * events, and a fake event for a jar the ledger does not know may well fail there.
     */
    private void publishTogether(Object... events) {
        tx.executeWithoutResult(s -> {
            for (Object event : events) {
                publisher.publishEvent(event);
            }
        });
        for (Object event : events) {
            awaitBudgetingDone(event);
        }
    }

    /** One transaction per event, each fully delivered before the next is published, as a real redelivery would be. */
    private void publishInSequence(Object... events) {
        for (Object event : events) {
            publishTogether(event);
        }
    }

    private void awaitBudgetingDone(Object event) {
        String id = eventId(event).toString();
        // a delivery that has not finished stays in event_publication (completion-mode=delete removes finished ones)
        await().atMost(WAIT)
                .pollInterval(Duration.ofMillis(50))
                .until(() ->
                        jdbc.sql("""
                                        SELECT count(*) FROM event_publication
                                        WHERE listener_id LIKE '%BudgetEventHandlers%' AND serialized_event LIKE ?
                                        """).param("%" + id + "%").query(Integer.class).single() == 0);
    }

    private static UUID eventId(Object event) {
        return event instanceof TransactionPosted p ? p.eventId() : ((TransactionRecategorized) event).eventId();
    }

    private BigDecimal spent(UUID jar) {
        return jdbc.sql("SELECT COALESCE(SUM(spent_amount), 0) FROM jar_periods WHERE jar_id = ?")
                .param(jar)
                .query(BigDecimal.class)
                .single();
    }

    private BigDecimal spentIn(UUID jar, LocalDate month) {
        return jdbc.sql("SELECT COALESCE(SUM(spent_amount), 0) FROM jar_periods WHERE jar_id = ? AND period_month = ?")
                .params(jar, month)
                .query(BigDecimal.class)
                .single();
    }

    @Test
    void anExpense_isCountedAsSpendingOfItsJarInItsMonth() {
        UserId user = new UserId(UUID.randomUUID());
        UUID jar = UUID.randomUUID();

        publishTogether(posted(user, jar, "EXPENSE", "85000", Instant.parse("2026-10-15T05:00:00Z")));

        assertThat(spentIn(jar, LocalDate.of(2026, 10, 1))).isEqualByComparingTo("85000");
    }

    @Test
    void theMonthIsDecidedInVietnamTime() {
        UserId user = new UserId(UUID.randomUUID());
        UUID jar = UUID.randomUUID();

        // 17:30 UTC on 31 October is 00:30 on 1 November in Vietnam; 16:30 UTC is still 23:30 on 31 October
        publishTogether(
                posted(user, jar, "EXPENSE", "1000", Instant.parse("2026-10-31T17:30:00Z")),
                posted(user, jar, "EXPENSE", "200", Instant.parse("2026-10-31T16:30:00Z")));

        assertThat(spentIn(jar, LocalDate.of(2026, 11, 1))).isEqualByComparingTo("1000");
        assertThat(spentIn(jar, LocalDate.of(2026, 10, 1))).isEqualByComparingTo("200");
    }

    @Test
    void aRedeliveredEvent_isCountedOnce() {
        UserId user = new UserId(UUID.randomUUID());
        UUID jar = UUID.randomUUID();
        TransactionPosted event = posted(user, jar, "EXPENSE", "1000", Instant.parse("2026-10-15T05:00:00Z"));

        publishInSequence(event, event); // the same eventId twice: the broker delivered it twice

        assertThat(spent(jar)).isEqualByComparingTo("1000");
        assertThat(jdbc.sql("SELECT count(*) FROM processed_messages WHERE consumer = ? AND message_id = ?")
                        .params("budgeting.transaction-posted", event.eventId().toString())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void incomeAllocationsAndJarTransfers_areNotSpending() {
        UserId user = new UserId(UUID.randomUUID());
        UUID jar = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-15T05:00:00Z");

        publishTogether(
                posted(user, jar, "INCOME", "5000", at), // income straight into a jar
                posted(user, null, "INCOME", "7000", at), // an income that was divided over jars (no single jar)
                posted(user, jar, "JAR_TRANSFER", "3000", at),
                posted(user, jar, "EXPENSE", "100", at));

        assertThat(spent(jar)).isEqualByComparingTo("100");
    }

    @Test
    void anExpenseThatMovesToAnotherJar_movesItsSpending() {
        UserId user = new UserId(UUID.randomUUID());
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-15T05:00:00Z");
        TransactionRecategorized move = recategorized(user, "EXPENSE", "500", at, from, to, null, null);

        publishTogether(posted(user, from, "EXPENSE", "500", at));
        publishInSequence(move, move); // the move is delivered twice

        assertThat(spent(from)).isEqualByComparingTo("0");
        assertThat(spent(to)).isEqualByComparingTo("500");
    }

    @Test
    void opposingMovesBetweenTheSameTwoJars_neverDeadlockAndCancelOut() {
        UserId user = new UserId(UUID.randomUUID());
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-15T05:00:00Z");
        Object[] events = new Object[16];
        for (int i = 0; i < events.length; i += 2) {
            events[i] = recategorized(user, "EXPENSE", "10", at, a, b, null, null);
            events[i + 1] = recategorized(user, "EXPENSE", "10", at, b, a, null, null);
        }

        publishTogether(events);

        assertThat(spent(a)).isEqualByComparingTo("0");
        assertThat(spent(b)).isEqualByComparingTo("0");
    }

    @Test
    void aChangeOfCategoryOnly_orOfAnIncome_doesNotTouchSpending() {
        UserId user = new UserId(UUID.randomUUID());
        UUID jar = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-15T05:00:00Z");

        publishTogether(
                posted(user, jar, "EXPENSE", "500", at),
                recategorized(user, "EXPENSE", "500", at, jar, jar, UUID.randomUUID(), UUID.randomUUID()),
                recategorized(user, "INCOME", "500", at, jar, other, null, null));

        assertThat(spent(jar)).isEqualByComparingTo("500");
        assertThat(spent(other)).isEqualByComparingTo("0");
    }
}
