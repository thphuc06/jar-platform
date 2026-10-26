package io.github.thphuc06.jars.platform.web;

import io.github.thphuc06.jars.shared.BusinessRuleViolationException;
import io.github.thphuc06.jars.shared.ConflictException;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.NotFoundException;
import io.github.thphuc06.jars.shared.ProblemTypes;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 problem (application/problem+json) with a {@code traceId}. Extends
 * {@link ResponseEntityExceptionHandler} so Spring MVC's own errors (malformed JSON, wrong method, unknown path, type
 * mismatch...) get the same shape. Never exposes stack traces or SQL to the client.
 */
@RestControllerAdvice
class ProblemDetailsExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsExceptionHandler.class);

    /** Business errors: the subclass decides the status, the code becomes the problem type. */
    @ExceptionHandler(DomainException.class)
    ProblemDetail domain(DomainException e) {
        HttpStatus status = switch (e) {
            case NotFoundException ignored -> HttpStatus.NOT_FOUND;
            case BusinessRuleViolationException ignored -> HttpStatus.UNPROCESSABLE_CONTENT;
            case ConflictException ignored -> HttpStatus.CONFLICT;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setType(ProblemTypes.of(e.code()));
        return withTraceId(problem);
    }

    /** A database constraint caught something the code did not check first: report a conflict, hide the SQL. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataIntegrity(DataIntegrityViolationException e) {
        log.warn("data integrity violation: {}", e.getMostSpecificCause().getMessage());
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with existing data");
        problem.setType(ProblemTypes.of("conflict"));
        return withTraceId(problem);
    }

    /** Anything unexpected: log everything (with the trace id from the MDC), tell the client almost nothing. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled exception", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error. Quote the traceId when reporting it.");
        problem.setType(ProblemTypes.of("internal-error"));
        return withTraceId(problem);
    }

    /** @Valid request body: 422 with one entry per invalid field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(ProblemDetailsExceptionHandler::fieldError)
                .toList();
        return ResponseEntity.unprocessableContent().body(validationProblem(errors));
    }

    /** Constraints on @RequestParam / @PathVariable (method validation): same 422 shape. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field",
                                result.getMethodParameter().getParameterName(),
                                "message",
                                String.valueOf(error.getDefaultMessage()))))
                .toList();
        return ResponseEntity.unprocessableContent().body(validationProblem(errors));
    }

    /** Every problem built by the base class (400, 404, 405, 415...) passes through here: add the trace id. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            withTraceId(problem);
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "The request has invalid fields");
        problem.setType(ProblemTypes.of("validation-failed"));
        problem.setProperty("errors", errors);
        return withTraceId(problem);
    }

    private static Map<String, String> fieldError(FieldError error) {
        return Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage()));
    }

    private static ProblemDetail withTraceId(ProblemDetail problem) {
        String traceId = TraceIdFilter.currentTraceId();
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }
}
