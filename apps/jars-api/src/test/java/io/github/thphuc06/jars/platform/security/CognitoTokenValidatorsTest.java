package io.github.thphuc06.jars.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class CognitoTokenValidatorsTest {

    static final String ISSUER = "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_TEST";
    static final OAuth2TokenValidator<Jwt> VALIDATOR = CognitoTokenValidators.forIssuer(ISSUER, List.of("web-client"));

    /** A token as Cognito issues it to our SPA; each test changes one thing. */
    private static Jwt token(UnaryOperator<Jwt.Builder> change) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("sub-123")
                .claim("token_use", "access")
                .claim("client_id", "web-client")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600));
        return change.apply(builder).build();
    }

    private static OAuth2TokenValidatorResult validate(UnaryOperator<Jwt.Builder> change) {
        return VALIDATOR.validate(token(change));
    }

    @Test
    void accessTokenForOurClient_isValid() {
        assertThat(validate(b -> b).hasErrors()).isFalse();
    }

    @Test
    void idToken_isRejected() {
        OAuth2TokenValidatorResult result = validate(b -> b.claim("token_use", "id"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors())
                .anySatisfy(e -> assertThat(e.getDescription()).contains("token_use"));
    }

    @Test
    void tokenForAnotherAppClient_isRejected() {
        assertThat(validate(b -> b.claim("client_id", "other-client")).hasErrors())
                .isTrue();
    }

    @Test
    void tokenFromAnotherUserPool_isRejected() {
        assertThat(validate(b -> b.issuer("https://cognito-idp.ap-southeast-1.amazonaws.com/other_pool"))
                        .hasErrors())
                .isTrue();
    }

    @Test
    void expiredToken_isRejected() {
        assertThat(validate(b -> b.issuedAt(Instant.now().minusSeconds(7200))
                                .expiresAt(Instant.now().minusSeconds(3600)))
                        .hasErrors())
                .isTrue();
    }
}
