package io.github.thphuc06.jars.budgeting.internal.service;

import io.github.thphuc06.jars.budgeting.MonthClosed;
import io.github.thphuc06.jars.budgeting.MonthClosed.JarPeriodSummary;
import io.github.thphuc06.jars.budgeting.internal.persistence.JarPeriodStore;
import io.github.thphuc06.jars.budgeting.internal.persistence.JarPeriodStore.Period;
import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.platform.idempotency.ProcessedMessages;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes budget months (flow F6). Safe to run any number of times for the same month:
 *
 * <ul>
 *   <li>per user, a marker {@code (budgeting.month-close, userId:month)} in {@code processed_messages} is written in
 *       the same transaction as the work, so a user's month is closed once; a repeat finds the marker and does nothing.
 *       Independently, {@code jar_periods.closed_at} is only set where it is still null and never moves.
 *   <li>for the whole batch, a marker {@code (budgeting.month-close.batch, month)} is written <em>after</em> every
 *       user succeeded. A duplicate SQS delivery after a complete run returns at once; a run that failed halfway has no
 *       marker, so the redelivery resumes and skips the users that are already closed.
 * </ul>
 *
 * Each user is closed in a transaction of its own, so one failing user rolls back only itself. Users are read in pages
 * of {@link #PAGE_SIZE} by keyset, never all at once.
 */
@Service
public class MonthCloseService {

    private static final Logger log = LoggerFactory.getLogger(MonthCloseService.class);

    public static final int PAGE_SIZE = 500;
    static final String USER_CONSUMER = "budgeting.month-close";
    static final String BATCH_CONSUMER = "budgeting.month-close.batch";

    /**
     * @param alreadyDone the whole month had been closed before; nothing was visited
     * @param users how many users were visited
     * @param closed how many of them were closed by this run (the rest were closed earlier)
     * @param failed how many failed; they are retried by running the job again
     */
    public record CloseReport(YearMonth month, boolean alreadyDone, int users, int closed, int failed) {}

    private final IdentityApi identity;
    private final LedgerApi ledger;
    private final JarPeriodStore periods;
    private final ProcessedMessages inbox;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    MonthCloseService(
            IdentityApi identity,
            LedgerApi ledger,
            JarPeriodStore periods,
            ProcessedMessages inbox,
            ApplicationEventPublisher events,
            TransactionTemplate tx,
            Clock clock) {
        this.identity = identity;
        this.ledger = ledger;
        this.periods = periods;
        this.inbox = inbox;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /** Closes {@code month} for every user. */
    public CloseReport closeAll(YearMonth month) {
        requireFinished(month);
        if (inbox.contains(BATCH_CONSUMER, month.toString())) {
            log.info("Month {} was already closed for everyone", month);
            return new CloseReport(month, true, 0, 0, 0);
        }
        int users = 0;
        int closed = 0;
        int failed = 0;
        UUID cursor = null;
        while (true) {
            List<UserId> page = identity.userIdsAfter(cursor, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (UserId user : page) {
                users++;
                try {
                    if (closeMonth(user, month)) {
                        closed++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.error("Closing {} failed for user {}", month, user, e);
                }
            }
            cursor = page.getLast().value();
        }
        if (failed == 0) {
            tx.executeWithoutResult(s -> inbox.firstTime(BATCH_CONSUMER, month.toString()));
        }
        log.info("Closed month {}: {} users visited, {} closed now, {} failed", month, users, closed, failed);
        return new CloseReport(month, false, users, closed, failed);
    }

    /**
     * Closes {@code month} for one user in its own transaction.
     *
     * @return true if this call closed it, false if it had been closed before
     * @throws IllegalArgumentException if the month has not finished yet (Vietnam time)
     */
    public boolean closeMonth(UserId userId, YearMonth month) {
        requireFinished(month);
        return Boolean.TRUE.equals(tx.execute(status -> doClose(userId, month)));
    }

    private boolean doClose(UserId userId, YearMonth month) {
        if (!inbox.firstTime(USER_CONSUMER, userId + ":" + month)) {
            return false;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDate closing = Months.startOf(month);
        LocalDate next = Months.startOf(month.plusMonths(1));

        List<JarSummary> jars = ledger.jars(userId);
        for (JarSummary jar : jars) {
            periods.ensure(userId, jar.id(), closing, now); // every active jar appears in the summary, even unused
            periods.ensure(userId, jar.id(), next, now); // and starts the next month with a record of its own
        }
        periods.closeAll(userId, closing, now);

        Map<UUID, String> names = new HashMap<>();
        jars.forEach(j -> names.put(j.id(), j.name()));
        List<Period> rows = periods.forMonth(userId, closing);
        if (rows.isEmpty()) {
            return true; // a user without any jar: nothing to report
        }
        List<JarPeriodSummary> summary = rows.stream()
                .map(p -> new JarPeriodSummary(
                        p.jarId(), names.getOrDefault(p.jarId(), "Hũ đã lưu trữ"), p.planned(), p.spent()))
                .toList();
        events.publishEvent(new MonthClosed(Uuids.v7(), userId, month, summary));
        return true;
    }

    /** A month can only be closed once it is over; this stops a wrong message from closing the running month. */
    private void requireFinished(YearMonth month) {
        if (!month.isBefore(Months.current(clock))) {
            throw new IllegalArgumentException("Month " + month + " has not finished yet");
        }
    }
}
