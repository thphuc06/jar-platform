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

    private BusinessMetrics() {}
}
