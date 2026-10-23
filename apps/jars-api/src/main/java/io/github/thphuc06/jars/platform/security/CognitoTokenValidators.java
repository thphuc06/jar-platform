package io.github.thphuc06.jars.platform.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Cognito access tokens carry no {@code aud} claim (unless resource binding is used), so audience validation is
 * replaced by two checks: the token must be an ACCESS token, issued to one of our app clients.
 */
final class CognitoTokenValidators {

    private CognitoTokenValidators() {}

    static OAuth2TokenValidator<Jwt> forIssuer(String issuer, Collection<String> allowedClientIds) {
        return new DelegatingOAuth2TokenValidator<>(List.of(
                JwtValidators.createDefaultWithIssuer(issuer), // signature is checked by the decoder; + exp/nbf + iss
                tokenUse("access"),
                clientIdIn(allowedClientIds)));
    }

    /** Rejects ID tokens: they describe the user to the client and must never be used to call an API. */
    static OAuth2TokenValidator<Jwt> tokenUse(String expected) {
        return jwt -> expected.equals(jwt.getClaimAsString("token_use"))
                ? OAuth2TokenValidatorResult.success()
                : failure("token_use must be '" + expected + "'");
    }

    /** Rejects tokens minted for other app clients of the same user pool. */
    static OAuth2TokenValidator<Jwt> clientIdIn(Collection<String> allowed) {
        return jwt -> allowed.contains(jwt.getClaimAsString("client_id"))
                ? OAuth2TokenValidatorResult.success()
                : failure("client_id is not allowed");
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
