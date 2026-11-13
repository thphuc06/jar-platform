package io.github.thphuc06.jars.ledger.internal.service;

import io.github.thphuc06.jars.ledger.internal.persistence.LedgerQueries;
import io.github.thphuc06.jars.ledger.internal.persistence.LedgerQueries.BalanceCheck;
import io.github.thphuc06.jars.ledger.internal.persistence.LedgerQueries.BalanceMismatch;
import io.github.thphuc06.jars.platform.observability.BusinessMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks the invariant of the ledger: the stored balance of every jar equals the sum of its (append-only) entries.
 * Nothing in a running system verified it before; the concurrency tests only assert it in tests.
 *
 * <p><b>Detect and report only.</b> A mismatch is logged and counted, never corrected: which side is wrong (the cached
 * balance or a missing entry) is a decision for a person with the ledger in front of them. The whole check is one
 * read-only statement ({@link LedgerQueries#checkBalances}), so running it twice, or concurrently with postings, is
 * harmless and needs no idempotency marker.
 *
 * <p>Metrics: {@code jars.ledger.reconciliation.mismatches} and {@code .jars.checked} are gauges holding the result
 * of the last run (see {@link BusinessMetrics#RECONCILIATION_MISMATCHES} for why not a counter), and
 * {@code jars.ledger.reconciliation.runs} counts runs by result.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    /** At most this many jars get a line of their own in the log, however many are inconsistent. */
    public static final int MAX_LOGGED_MISMATCHES = 50;

    /**
     * @param jarsChecked how many jars were compared
     * @param mismatches how many of them are inconsistent
     * @param reported the first {@link #MAX_LOGGED_MISMATCHES} of them, largest absolute difference first
     * @param took wall-clock time of the check
     */
    public record ReconciliationReport(
            long jarsChecked, long mismatches, List<BalanceMismatch> reported, Duration took) {

        public boolean clean() {
            return mismatches == 0;
        }
    }

    private final LedgerQueries queries;
    private final MeterRegistry meters;
    private final TransactionTemplate readOnly;

    // gauges keep only a weak reference to what they read: these fields are the strong ones
    private final AtomicLong lastMismatches = new AtomicLong();
    private final AtomicLong lastChecked = new AtomicLong();
    private boolean gaugesRegistered;

    ReconciliationService(LedgerQueries queries, MeterRegistry meters, PlatformTransactionManager transactions) {
        this.queries = queries;
        this.meters = meters;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /**
     * Runs one reconciliation of all jars.
     *
     * @throws RuntimeException when the database cannot be read; the run is counted with result {@code error} and the
     *     caller (the queue listener) lets the message be delivered again
     */
    public ReconciliationReport reconcile() {
        long started = System.nanoTime();
        BalanceCheck check;
        try {
            check = readOnly.execute(status -> queries.checkBalances(MAX_LOGGED_MISMATCHES));
        } catch (RuntimeException e) {
            meters.counter(BusinessMetrics.RECONCILIATION_RUNS, "result", "error")
                    .increment();
            log.error("Ledger reconciliation failed", e);
            throw e;
        }
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        ReconciliationReport report =
                new ReconciliationReport(check.jarsChecked(), check.mismatches(), check.worst(), took);

        publish(report);
        logReport(report);
        return report;
    }

    private void logReport(ReconciliationReport report) {
        for (BalanceMismatch m : report.reported()) {
            // ids only: no user email or jar name in a log line
            log.error(
                    "Ledger mismatch: jar={} stored={} computed={} difference={}",
                    m.jarId(),
                    m.stored().toPlainString(),
                    m.computed().toPlainString(),
                    m.difference().toPlainString());
        }
        if (report.mismatches() > report.reported().size()) {
            log.error(
                    "Ledger mismatch: {} more jars are inconsistent and not listed (limit {} lines per run)",
                    report.mismatches() - report.reported().size(),
                    MAX_LOGGED_MISMATCHES);
        }
        log.info(
                "Ledger reconciliation finished: {} jars checked, {} mismatches, took {} ms",
                report.jarsChecked(),
                report.mismatches(),
                report.took().toMillis());
    }

    private synchronized void publish(ReconciliationReport report) {
        lastMismatches.set(report.mismatches());
        lastChecked.set(report.jarsChecked());
        if (!gaugesRegistered) {
            // registered by the first run, not at startup: a task that has not run yet must report nothing instead of
            // a 0 that would clear an alarm raised by the previous task
            meters.gauge(BusinessMetrics.RECONCILIATION_MISMATCHES, lastMismatches);
            meters.gauge(BusinessMetrics.RECONCILIATION_JARS_CHECKED, lastChecked);
            gaugesRegistered = true;
        }
        meters.counter(BusinessMetrics.RECONCILIATION_RUNS, "result", report.clean() ? "clean" : "mismatch")
                .increment();
    }
}
