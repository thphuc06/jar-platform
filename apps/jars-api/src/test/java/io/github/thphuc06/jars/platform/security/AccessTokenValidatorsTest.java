package io.github.thphuc06.jars.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class AccessTokenValidatorsTest {

    private static final String ISSUER = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_test";

    @Test
    void accessToken_fromAllowedClient_isAccepted() {
        Jwt jwt = token("access", "client-a", ISSUER, Instant.now().plusSeconds(600));

        assertThat(validate(jwt, List.of("client-a", "client-b")).hasErrors()).isFalse();
    }

    @Test
    void idToken_isRejected_evenWithValidSignatureAndClient() {
        Jwt jwt = token("id", "client-a", ISSUER, Instant.now().plusSeconds(600));

        OAuth2TokenValidatorResult result = validate(jwt, List.of("client-a"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(e -> e.getDescription().contains("token_use"));
    }

    @Test
    void tokenFromUnknownClient_isRejected() {
        Jwt jwt = token("access", "someone-else", ISSUER, Instant.now().plusSeconds(600));

        OAuth2TokenValidatorResult result = validate(jwt, List.of("client-a"));

        assertThat(result.getErrors()).anyMatch(e -> e.getDescription().contains("client_id"));
    }

    @Test
    void noConfiguredClients_failsClosed() {
        Jwt jwt = token("access", "client-a", ISSUER, Instant.now().plusSeconds(600));

        assertThat(validate(jwt, List.of()).hasErrors()).isTrue();
    }

    @Test
    void tokenFromAnotherIssuer_isRejected() {
        Jwt jwt = token(
                "access", "client-a", "https://evil.example.com", Instant.now().plusSeconds(600));

        assertThat(validate(jwt, List.of("client-a")).hasErrors()).isTrue();
    }

    @Test
    void expiredToken_isRejected() {
        Jwt jwt = token("access", "client-a", ISSUER, Instant.now().minusSeconds(3600));

        assertThat(validate(jwt, List.of("client-a")).hasErrors()).isTrue();
    }

    private static OAuth2TokenValidatorResult validate(Jwt jwt, List<String> allowedClients) {
        return AccessTokenValidators.forIssuer(ISSUER, allowedClients).validate(jwt);
    }

    private static Jwt token(String tokenUse, String clientId, String issuer, Instant expiresAt) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("11111111-2222-3333-4444-555555555555")
                .issuer(issuer)
                .claim("token_use", tokenUse)
                .claim("client_id", clientId)
                .issuedAt(expiresAt.minusSeconds(7200))
                .expiresAt(expiresAt)
                .build();
    }
}
