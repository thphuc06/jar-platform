package io.github.thphuc06.jars.identity.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.identity.CurrentUser;
import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.platform.security.SecurityConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Web + security slice: real SecurityFilterChain, mocked identity module, tokens built by spring-security-test. */
@WebMvcTest(MeController.class)
@Import(SecurityConfig.class)
class MeControllerTest {

    static final SimpleGrantedAuthority READ = new SimpleGrantedAuthority("SCOPE_jars-api/read");

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    IdentityApi identity;

    @Test
    void me_withReadScope_returnsCurrentUser() {
        UUID id = UUID.fromString("0193b2c4-0000-7000-8000-000000000001");
        given(identity.currentUser())
                .willReturn(
                        new CurrentUser(id, "an@example.com", null, "vi-VN", Instant.parse("2026-10-23T00:00:00Z")));

        assertThat(mvc.get().uri("/api/v1/me").with(jwt().authorities(READ)))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"id": "0193b2c4-0000-7000-8000-000000000001", "email": "an@example.com", "locale": "vi-VN"}
                        """);
    }

    @Test
    void me_withoutToken_is401ProblemWithBearerChallenge() {
        assertThat(mvc.get().uri("/api/v1/me"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .satisfies(result -> assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        // Spring Security 7 also advertises RFC 9728 protected-resource metadata in the challenge
                        .startsWith("Bearer"))
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"type": "https://jars.local/problems/unauthorized", "status": 401, "instance": "/api/v1/me"}
                        """);
    }

    @Test
    void me_withTokenButWithoutReadScope_is403Problem() {
        assertThat(mvc.get().uri("/api/v1/me").with(jwt().authorities()))
                .hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.type")
                .isEqualTo("https://jars.local/problems/forbidden");
    }

    @Test
    void writeRequest_withReadScopeOnly_is403() {
        // any non-GET /api request needs SCOPE_jars-api/write, checked before the controller is even looked up
        assertThat(mvc.post().uri("/api/v1/me").with(jwt().authorities(READ))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void protectedResourceMetadata_advertisesCognitoIssuerAndScopes() {
        assertThat(mvc.get().uri("/.well-known/oauth-protected-resource"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {
                          "authorization_servers": ["https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_QeA6qyznX"],
                          "scopes_supported": ["jars-api/read", "jars-api/write"],
                          "tls_client_certificate_bound_access_tokens": false
                        }
                        """);
    }

    @Test
    void unknownPathOutsideApi_isDeniedByDefault() {
        assertThat(mvc.get().uri("/internal/secret").with(jwt().authorities(READ)))
                .hasStatus(HttpStatus.FORBIDDEN);
    }
}
