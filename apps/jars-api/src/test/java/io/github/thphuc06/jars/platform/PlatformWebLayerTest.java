package io.github.thphuc06.jars.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.platform.security.SecurityConfig;
import io.github.thphuc06.jars.platform.security.UserDirectory;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.ProblemTypes;
import io.github.thphuc06.jars.shared.UserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform web layer end to end without a database: authentication errors, ProblemDetail shape, trace ids and
 * how the current user is resolved. A tiny controller below stands in for real endpoints.
 */
@WebMvcTest(PlatformWebLayerTest.ProbeController.class)
// nested classes of a test are excluded from component scanning, so the probe controller is imported explicitly
@Import({SecurityConfig.class, PlatformWebLayerTest.ProbeController.class})
class PlatformWebLayerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    UserDirectory userDirectory;

    @Test
    void protectedEndpoint_withoutToken_is401ProblemJson() {
        assertThat(mvc.get().uri("/probe/ok"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body)
                        .contains("\"type\":\"https://jars.local/problems/unauthenticated\"")
                        .contains("\"status\":401")
                        .contains("\"traceId\""));
    }

    @Test
    void protectedEndpoint_withToken_isAllowed() {
        assertThat(mvc.get().uri("/probe/ok").with(jwt())).hasStatusOk();
    }

    @Test
    void everyResponse_carriesATraceparentHeader() {
        String header =
                mvc.get().uri("/probe/ok").with(jwt()).exchange().getResponse().getHeader("traceparent");

        assertThat(header).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
    }

    @Test
    void incomingTraceparent_isReusedSoTracesCanBeStitched() {
        String incoming = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        String header = mvc.get()
                .uri("/probe/ok")
                .header("traceparent", incoming)
                .with(jwt())
                .exchange()
                .getResponse()
                .getHeader("traceparent");

        assertThat(header).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-");
    }

    @Test
    void domainException_isMappedToItsProblemType() {
        assertThat(mvc.get().uri("/probe/domain").with(jwt()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body)
                        .contains("\"type\":\"https://jars.local/problems/not-found\"")
                        .contains("Hũ không tồn tại")
                        .contains("\"instance\":\"/probe/domain\""));
    }

    @Test
    void beanValidationFailure_is422WithFieldErrors() {
        assertThat(mvc.post()
                        .uri("/probe/validate")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"amount\":-5}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body)
                        .contains("\"type\":\"https://jars.local/problems/validation-failed\"")
                        .contains("\"field\":\"name\"")
                        .contains("\"field\":\"amount\""));
    }

    @Test
    void malformedJson_is400() {
        assertThat(mvc.post()
                        .uri("/probe/validate")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void optimisticLockFailure_is409() {
        assertThat(mvc.get().uri("/probe/lock").with(jwt()))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body).contains("version-conflict"));
    }

    @Test
    void unexpectedException_is500WithoutLeakingInternals() {
        assertThat(mvc.get().uri("/probe/boom").with(jwt()))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body)
                        .contains("internal-error")
                        .contains("\"traceId\"")
                        .doesNotContain("secret-internal-detail")
                        .doesNotContain("RuntimeException")
                        .doesNotContain("at io.github"));
    }

    @Test
    void userIdParameter_isResolvedFromTheTokenSubject() {
        UserId expected = UserId.of("0193b2c0-0000-7000-8000-000000000001");
        when(userDirectory.resolve("cognito-sub-123")).thenReturn(expected);

        assertThat(mvc.get().uri("/probe/me").with(jwt().jwt(j -> j.subject("cognito-sub-123"))))
                .hasStatusOk()
                .bodyText()
                .isEqualTo(expected.toString());
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        record Body(@NotBlank String name, @Positive long amount) {}

        @GetMapping("/ok")
        String ok() {
            return "ok";
        }

        @GetMapping("/domain")
        String domain() {
            throw DomainException.notFound("Hũ");
        }

        @PostMapping("/validate")
        String validate(@Valid @RequestBody Body body) {
            return body.name();
        }

        @GetMapping("/lock")
        String lock() {
            throw new ObjectOptimisticLockingFailureException(Object.class, 1L);
        }

        @GetMapping("/boom")
        String boom() {
            throw new RuntimeException("secret-internal-detail");
        }

        @GetMapping("/me")
        String me(UserId userId) {
            return userId.toString();
        }
    }

    // keeps the import used if a test above is removed
    @SuppressWarnings("unused")
    private static final Object REFERENCE = ProblemTypes.NOT_FOUND;
}
