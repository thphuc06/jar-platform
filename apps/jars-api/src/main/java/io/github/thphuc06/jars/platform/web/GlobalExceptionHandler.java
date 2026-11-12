package io.github.thphuc06.jars.platform.web;

import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into {@code application/problem+json} with a {@code traceId}. Spring MVC's own exceptions are
 * handled by the base class; this adds domain errors, validation details, optimistic-lock conflicts and a catch-all
 * that never leaks stack traces.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetails.of(ex.type(), ex.getMessage(), request.getRequestURI());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus());
        ex.retryAfterSeconds().ifPresent(seconds -> response.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds)));
        return response.body(problem);
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    ProblemDetail handleConcurrencyFailure(ConcurrencyFailureException ex, HttpServletRequest request) {
        return ProblemDetails.of(
                ProblemTypes.VERSION_CONFLICT,
                "Dữ liệu vừa được cập nhật bởi một yêu cầu khác, vui lòng thử lại.",
                request.getRequestURI());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getConstraintViolations()
                .forEach(v -> errors.add(Map.of(
                        "field", v.getPropertyPath().toString(),
                        "message", String.valueOf(v.getMessage()))));
        return ProblemDetails.withErrors(
                ProblemDetails.of(
                        ProblemTypes.VALIDATION_FAILED, "Dữ liệu gửi lên không hợp lệ.", request.getRequestURI()),
                errors);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return ProblemDetails.of(
                ProblemTypes.INTERNAL_ERROR,
                "Đã xảy ra lỗi hệ thống. Hãy gửi kèm traceId khi báo lỗi.",
                request.getRequestURI());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(e -> errors.add(Map.of(
                        "field", e.getField(),
                        "message", String.valueOf(e.getDefaultMessage()))));
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(e -> errors.add(
                        Map.of("field", e.getObjectName(), "message", String.valueOf(e.getDefaultMessage()))));
        return validationResponse(errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String field = result.getMethodParameter().getParameterName();
            result.getResolvableErrors()
                    .forEach(e -> errors.add(
                            Map.of("field", String.valueOf(field), "message", String.valueOf(e.getDefaultMessage()))));
        });
        return validationResponse(errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            ProblemDetails.withTraceId(problem);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationResponse(List<Map<String, String>> errors, WebRequest request) {
        String path = request.getDescription(false).replaceFirst("^uri=", "");
        ProblemDetail problem = ProblemDetails.withErrors(
                ProblemDetails.of(ProblemTypes.VALIDATION_FAILED, "Dữ liệu gửi lên không hợp lệ.", path), errors);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
