package io.github.thphuc06.jars;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Starts the application with the profile it runs with on ECS. Every other test runs under the default {@code local}
 * profile, so without this a bean that is only valid locally (or a missing one) would first fail in production.
 * The Docker image once crashed at startup for exactly that reason.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("aws")
@Import(TestcontainersConfiguration.class)
class AwsProfileTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Test
    void theApplicationStartsAndServesTheProbes() {
        assertThat(mvc.get().uri("/api/v1/health")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/health/liveness")).hasStatusOk();
    }

    @Test
    void localOnlyDevelopmentToolsDoNotExist() {
        assertThat(mvc.post().uri("/dev/token").contentType("application/json").content("{}"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(context.containsBean("localDevJwtDecoder")).isFalse();
        assertThat(context.containsBean("localDevKey")).isFalse();
    }

    @Test
    void apiDocumentationIsNotPublished() {
        assertThat(mvc.get().uri("/v3/api-docs")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void onlyCognitoTokensCanBeVerified_andWithoutConfiguredIssuerNoneIs() {
        assertThat(context.getBean(JwtDecoder.class)).isNotNull();
        assertThat(mvc.get().uri("/api/v1/me")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/v1/me").header("Authorization", "Bearer abc.def.ghi"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
