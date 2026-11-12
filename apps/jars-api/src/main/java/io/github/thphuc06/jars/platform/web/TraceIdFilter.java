package io.github.thphuc06.jars.platform.web;

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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a trace id: reuses the one in an incoming W3C {@code traceparent} header or creates a new one,
 * exposes it in the MDC (key {@value #MDC_KEY}) for logs and error bodies, and returns a {@code traceparent} header.
 * Runs before Spring Security so even 401 responses carry the id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "traceId";
    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = traceIdFrom(request.getHeader("traceparent"));
        MDC.put(MDC_KEY, traceId);
        response.setHeader("traceparent", "00-" + traceId + "-" + randomHex(8) + "-01");
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
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
