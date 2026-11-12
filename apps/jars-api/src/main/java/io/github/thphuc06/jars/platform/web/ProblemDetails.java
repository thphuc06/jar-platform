package io.github.thphuc06.jars.platform.web;

import io.github.thphuc06.jars.shared.ProblemType;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 bodies in the shape documented in docs/03-api/api-contract.md. */
public final class ProblemDetails {

    private ProblemDetails() {}

    public static ProblemDetail of(ProblemType type, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(type.status()), detail);
        problem.setType(URI.create(type.uri()));
        problem.setTitle(type.title());
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        return withTraceId(problem);
    }

    public static ProblemDetail withErrors(ProblemDetail problem, List<Map<String, String>> errors) {
        problem.setProperty("errors", errors);
        return problem;
    }

    static ProblemDetail withTraceId(ProblemDetail problem) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }
}
