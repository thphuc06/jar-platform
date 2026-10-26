package io.github.thphuc06.jars.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a trace id (W3C Trace Context): reuses the caller's {@code traceparent} if valid, otherwise
 * starts a new trace. The id is put in the logging MDC (every log line shows it), echoed in the response header and
 * copied into every problem body, so a user-reported error can be found in the logs.
 *
 * <p>Runs before Spring Security so that 401/403 responses carry it too. Replaced by OpenTelemetry in Phase 6.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    static final String HEADER = "traceparent";
    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
    private static final String INVALID_TRACE_ID = "0".repeat(32);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = incomingTraceId(request.getHeader(HEADER));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, "00-" + traceId + "-" + randomHex(8) + "-01");
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY); // threads are reused (also virtual-thread carriers): never leak an id
        }
    }

    /** The trace id of the current request, or {@code null} outside a request. */
    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }

    static String incomingTraceId(String traceparent) {
        if (traceparent != null) {
            Matcher m = TRACEPARENT.matcher(traceparent.trim());
            if (m.matches() && !INVALID_TRACE_ID.equals(m.group(1))) {
                return m.group(1);
            }
        }
        return randomHex(16);
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
