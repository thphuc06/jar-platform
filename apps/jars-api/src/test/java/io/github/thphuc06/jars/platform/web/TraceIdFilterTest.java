package io.github.thphuc06.jars.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
