package io.github.thphuc06.jars.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    final TraceIdFilter filter = new TraceIdFilter();

    /** Runs the filter and returns the trace id that was visible in the MDC while the request was processed. */
    private String traceIdSeenDownstream(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(MDC.get(TraceIdFilter.MDC_KEY));
            }
        });
        return seen.get();
    }

    @Test
    void validIncomingTraceparent_isContinued() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String traceId = traceIdSeenDownstream(request, response);

        assertThat(traceId).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(response.getHeader("traceparent"))
                .startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-")
                .doesNotContain("00f067aa0ba902b7"); // our own span id, not the caller's
    }

    @Test
    void missingOrInvalidTraceparent_startsANewTrace() throws Exception {
        for (String header : new String[] {null, "garbage", "00-" + "0".repeat(32) + "-00f067aa0ba902b7-01"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            if (header != null) {
                request.addHeader("traceparent", header);
            }

            String traceId = traceIdSeenDownstream(request, new MockHttpServletResponse());

            assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32));
        }
    }

    @Test
    void mdcIsCleanedAfterTheRequest() throws Exception {
        traceIdSeenDownstream(new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }
}
