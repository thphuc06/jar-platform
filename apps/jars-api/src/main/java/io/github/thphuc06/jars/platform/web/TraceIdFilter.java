package io.github.thphuc06.jars.platform.web;

import io.github.thphuc06.jars.platform.observability.MdcKeys;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a trace id, exposes it in the MDC (key {@value #MDC_KEY}) for logs and error bodies, and returns
 * it in a W3C {@code traceparent} response header. Runs before Spring Security so even 401 responses carry the id. It
 * also owns the end of the request's MDC: the user id that {@code CurrentUser} adds once the caller is known is removed
 * here, in the same {@code finally}.
 *
 * <p>With tracing on (OpenTelemetry, the {@code aws} profile) the id is the one of the current span, which Spring's
 * observation filter has already started from an incoming {@code traceparent}: the id a user reads in an error body is
 * the id of the trace in X-Ray. Without a tracer (tests, a disabled tracer) the filter falls back to reusing the
 * incoming {@code traceparent} or creating an id, as before tracing existed.
 */
@Component
// after ServerHttpObservationFilter (HIGHEST_PRECEDENCE + 1), which opens the request span; long before Security (-100)
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class TraceIdFilter extends OncePerRequestFilter {

    static final String MDC_KEY = MdcKeys.TRACE_ID;
    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final ObjectProvider<Tracer> tracer;

    TraceIdFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceparent = currentSpanTraceparent();
        if (traceparent == null) {
            traceparent = "00-" + traceIdFrom(request.getHeader("traceparent")) + "-" + randomHex(8) + "-01";
        }
        MDC.put(MDC_KEY, traceparent.substring(3, 35));
        response.setHeader("traceparent", traceparent);
        try {
            chain.doFilter(request, response);
        } finally {
            // the entries of this request, and only of this request: the thread serves the next one
            MDC.remove(MDC_KEY);
            MDC.remove(MdcKeys.USER_ID);
        }
    }

    /** {@code traceparent} of the span the observation filter opened, or null when there is no tracing. */
    private String currentSpanTraceparent() {
        Tracer t = tracer.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext context = span.context();
        if (context.traceId().length() != 32 || context.spanId().length() != 16) {
            return null; // a no-op tracer reports empty ids
        }
        return "00-" + context.traceId() + "-" + context.spanId()
                + (Boolean.TRUE.equals(context.sampled()) ? "-01" : "-00");
    }

    static String traceIdFrom(String traceparent) {
        if (traceparent != null) {
            Matcher m = TRACEPARENT.matcher(traceparent);
            if (m.matches() && !m.group(1).equals("0".repeat(32))) {
                return m.group(1);
            }
        }
        return randomHex(16);
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HEX.formatHex(buffer);
    }
}
