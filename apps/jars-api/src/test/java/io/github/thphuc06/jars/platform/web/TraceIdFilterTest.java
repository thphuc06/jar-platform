package io.github.thphuc06.jars.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    @Test
    void validTraceparent_yieldsItsTraceId() {
        String id = TraceIdFilter.traceIdFrom("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        assertThat(id).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void missingOrMalformedTraceparent_yieldsANewIdEachTime() {
        String a = TraceIdFilter.traceIdFrom(null);
        String b = TraceIdFilter.traceIdFrom("garbage");

        assertThat(a).matches("[0-9a-f]{32}");
        assertThat(b).matches("[0-9a-f]{32}").isNotEqualTo(a);
    }

    @Test
    void allZeroTraceId_isInvalidPerW3cAndReplaced() {
        String id = TraceIdFilter.traceIdFrom("00-" + "0".repeat(32) + "-00f067aa0ba902b7-01");

        assertThat(id).isNotEqualTo("0".repeat(32)).matches("[0-9a-f]{32}");
    }

    @Test
    void withACurrentSpan_logsAndReturnsTheSpansIds() throws Exception {
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        when(context.spanId()).thenReturn("b7ad6b7169203331");
        when(context.sampled()).thenReturn(true);
        Span span = mock(Span.class);
        when(span.context()).thenReturn(context);
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(span);

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> logged = new AtomicReference<>();
        filter(tracer).doFilter(new MockHttpServletRequest(), response, (req, res) -> logged.set(MDC.get("traceId")));

        assertThat(logged.get()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(response.getHeader("traceparent"))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-b7ad6b7169203331-01");
        assertThat(MDC.get("traceId")).as("cleared after the request").isNull();
    }

    @Test
    void withoutATracer_reusesTheIncomingTraceIdWithANewSpanId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(null).doFilter(request, response, (req, res) -> {});

        assertThat(response.getHeader("traceparent"))
                .startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-")
                .doesNotContain("00f067aa0ba902b7")
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
    }

    private static TraceIdFilter filter(Tracer tracer) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (tracer != null) {
            beans.registerSingleton("tracer", tracer);
        }
        return new TraceIdFilter(beans.getBeanProvider(Tracer.class));
    }
}
