package io.github.thphuc06.jars.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.platform.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The error contract of docs/03-api/api-contract.md §1, asserted end to end through the real filter chain. */
@WebMvcTest(ErrorProbeController.class)
@Import({SecurityConfig.class, TraceIdFilter.class, ProblemDetailsExceptionHandler.class})
class ProblemDetailsExceptionHandlerTest {

    static final SimpleGrantedAuthority READ = new SimpleGrantedAuthority("SCOPE_jars-api/read");
    static final SimpleGrantedAuthority WRITE = new SimpleGrantedAuthority("SCOPE_jars-api/write");
    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Autowired
    MockMvcTester mvc;

    private MvcTestResult get(String uri) {
        return mvc.get()
                .uri(uri)
                .header("traceparent", TRACEPARENT)
                .with(jwt().authorities(READ))
                .exchange();
    }

    private MvcTestResult postJson(String uri, String json) {
        return mvc.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .header("traceparent", TRACEPARENT)
                .with(jwt().authorities(WRITE))
                .exchange();
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result)
                .hasStatus(status)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"type": "https://jars.local/problems/%s", "status": %d,
                         "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"}
                        """.formatted(code, status.value()));
    }

    @Test
    void invalidBody_is422WithOneErrorPerField() {
        MvcTestResult result = postJson("/api/v1/_probe/things", "{\"name\": \"\", \"amount\": -5}");

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "validation-failed");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("name", "amount");
    }

    @Test
    void invalidRequestParam_is422() {
        MvcTestResult result = get("/api/v1/_probe/page?limit=500");

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "validation-failed");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("limit");
    }

    @Test
    void malformedJson_is400() {
        assertThat(postJson("/api/v1/_probe/things", "{not json")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void wrongTypeInPath_is400() {
        assertThat(get("/api/v1/_probe/things/not-a-uuid"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.traceId")
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void notFound_is404WithResourceSpecificType() {
        assertProblem(
                get("/api/v1/_probe/things/0193b2c4-0000-7000-8000-000000000001"),
                HttpStatus.NOT_FOUND,
                "thing-not-found");
    }

    @Test
    void businessRule_is422WithItsCode() {
        assertProblem(get("/api/v1/_probe/rule"), HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-jar-balance");
    }

    @Test
    void conflict_is409WithItsCode() {
        assertProblem(get("/api/v1/_probe/conflict"), HttpStatus.CONFLICT, "email-already-registered");
    }

    @Test
    void databaseConstraint_is409AndHidesTheSql() {
        MvcTestResult result = get("/api/v1/_probe/db");

        assertProblem(result, HttpStatus.CONFLICT, "conflict");
        assertThat(result).bodyText().doesNotContain("secret_index").doesNotContain("duplicate key");
    }

    @Test
    void concurrentModificationAfterRetries_is409AndHidesTheEntity() {
        MvcTestResult result = get("/api/v1/_probe/stale");

        assertProblem(result, HttpStatus.CONFLICT, "concurrent-modification");
        assertThat(result).bodyText().doesNotContain("ledger.internal");
    }

    @Test
    void unexpectedException_is500AndLeaksNothing() {
        MvcTestResult result = get("/api/v1/_probe/boom");

        assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error");
        assertThat(result)
                .bodyText()
                .doesNotContain("hunter2")
                .doesNotContain("IllegalStateException")
                .doesNotContain("at io.github");
    }

    @Test
    void unknownApiPath_is404Problem() {
        assertThat(get("/api/v1/_probe/does-not-exist"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void securityErrors_carryTheSameTraceId() {
        assertThat(mvc.get().uri("/api/v1/_probe/rule").header("traceparent", TRACEPARENT))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .extractingPath("$.traceId")
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }
}
