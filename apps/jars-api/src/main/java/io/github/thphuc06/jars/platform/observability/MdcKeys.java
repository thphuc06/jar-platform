package io.github.thphuc06.jars.platform.observability;

/**
 * Keys of the logging MDC that every log line of a request carries. {@code traceId} is set by the trace filter;
 * {@code userId} (the internal user id, never the e-mail address) as soon as the caller is resolved, so a log line can
 * be matched with the {@code audit_log} rows of the same user and trace. The trace filter removes both when the
 * request ends: threads are reused (and virtual threads are cheap, but not free of leftovers).
 */
public final class MdcKeys {

    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";

    private MdcKeys() {}
}
