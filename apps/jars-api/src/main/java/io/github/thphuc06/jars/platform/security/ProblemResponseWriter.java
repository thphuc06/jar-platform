package io.github.thphuc06.jars.platform.security;

import io.github.thphuc06.jars.platform.web.ProblemDetails;
import io.github.thphuc06.jars.shared.ProblemType;
import io.github.thphuc06.jars.shared.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes 401/403 as {@code application/problem+json}, like every other error, because Spring Security's filters run
 * before the MVC exception handler.
 */
class ProblemResponseWriter {

    private final JsonMapper jsonMapper;
    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();

    ProblemResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    void unauthenticated(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        // sets status 401 and the WWW-Authenticate header (with the invalid_token reason when there is one)
        bearer.commence(request, response, ex);
        write(request, response, ProblemTypes.UNAUTHENTICATED, "Thiếu hoặc sai access token.");
    }

    void forbidden(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        write(request, response, ProblemTypes.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, ProblemType type, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetails.of(type, detail, request.getRequestURI());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(jsonMapper.writeValueAsString(problem));
    }
}
