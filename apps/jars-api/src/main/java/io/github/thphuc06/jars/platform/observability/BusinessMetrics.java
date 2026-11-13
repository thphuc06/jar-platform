package io.github.thphuc06.jars.platform.observability;

/**
 * Names of the business metrics. They are the only application metrics that reach CloudWatch: the ADOT collector keeps
 * metrics whose name starts with {@value #PREFIX} and drops the rest (JVM, HTTP), because every metric and dimension
 * combination is a paid custom metric. Request rate, latency and errors come from the free ALB metrics instead.
 *
 * <p>Tags must stay low-cardinality (a handful of values): never a user id, a jar id or an amount.
 */
public final class BusinessMetrics {

    public static final String PREFIX = "jars.";

    /** Counter of committed transactions, tags {@code type} (INCOME, EXPENSE, ...) and {@code source}. */
    public static final String TRANSACTIONS_POSTED = PREFIX + "transactions.posted";

    /** Counter of committed anomalies, tags {@code detector} (MAD, CUSUM) and {@code severity}. */
    public static final String ANOMALIES_DETECTED = PREFIX + "anomalies.detected";

    /** Observation (timer and span) of one month close run, tag {@code outcome}. */
    public static final String MONTH_CLOSE = PREFIX + "month.close";

    /**
     * Gauge: how many jars the last nightly reconciliation found with {@code balance != SUM(entries)}. A gauge, not a
     * counter, on purpose: the ADOT/EMF exporter turns a cumulative counter into deltas and drops the first point of
     * a series, so a count that is incremented once in a task's lifetime would never reach CloudWatch, whereas a gauge
     * is reported every minute with the value of the last run. Not registered until the first run of a task, so a
     * fresh task reports nothing rather than a false 0. No tags (one series).
     */
    public static final String RECONCILIATION_MISMATCHES = PREFIX + "ledger.reconciliation.mismatches";

    /** Gauge: how many jars the last nightly reconciliation checked (same life cycle as the mismatches gauge). */
    public static final String RECONCILIATION_JARS_CHECKED = PREFIX + "ledger.reconciliation.jars.checked";

    /** Counter of reconciliation runs, tag {@code result} ({@code clean}, {@code mismatch}, {@code error}). */
    public static final String RECONCILIATION_RUNS = PREFIX + "ledger.reconciliation.runs";

    private BusinessMetrics() {}
}
