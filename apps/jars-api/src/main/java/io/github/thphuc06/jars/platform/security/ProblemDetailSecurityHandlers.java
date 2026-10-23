package io.github.thphuc06.jars.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security errors happen in the filter chain, before any controller runs, so @RestControllerAdvice cannot handle
 * them. These handlers keep the RFC 6750 {@code WWW-Authenticate} header and add an RFC 9457 problem body.
 */
final class ProblemDetailSecurityHandlers {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ProblemDetailSecurityHandlers() {}

    static AuthenticationEntryPoint authenticationEntryPoint() {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            bearer.commence(request, response, exception); // 401 + WWW-Authenticate: Bearer ...
            write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", "Missing or invalid access token");
        };
    }

    static AccessDeniedHandler accessDeniedHandler() {
        BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, exception) -> {
            bearer.handle(request, response, exception); // 403 + WWW-Authenticate: Bearer error="insufficient_scope"
            write(
                    request,
                    response,
                    HttpStatus.FORBIDDEN,
                    "forbidden",
                    "The access token does not have the scope required for this operation");
        };
    }

    private static void write(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String type, String detail)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://jars.local/problems/" + type);
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JSON.writeValue(response.getOutputStream(), problem);
    }
}
