package io.github.thphuc06.jars.platform.security;

import java.util.Collection;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Extra checks for Cognito <em>access</em> tokens on top of signature, expiry and issuer
 * (see docs/02-architecture/security.md §1): the token must be an access token (not an ID token) and must come from
 * one of our app clients.
 */
public final class AccessTokenValidators {

    private AccessTokenValidators() {}

    public static OAuth2TokenValidator<Jwt> tokenUseIsAccess() {
        return jwt -> "access".equals(jwt.getClaimAsString("token_use"))
                ? OAuth2TokenValidatorResult.success()
                : failure("token_use must be 'access'");
    }

    /** Fails closed: with no allowed client configured every token is rejected. */
    public static OAuth2TokenValidator<Jwt> clientIdIn(Collection<String> allowedClientIds) {
        Set<String> allowed = Set.copyOf(allowedClientIds);
        return jwt -> allowed.contains(jwt.getClaimAsString("client_id"))
                ? OAuth2TokenValidatorResult.success()
                : failure("client_id is not allowed");
    }

    /** Default checks (signature already verified by the decoder, plus exp/nbf and issuer) + the two above. */
    public static OAuth2TokenValidator<Jwt> forIssuer(String issuer, Collection<String> allowedClientIds) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), tokenUseIsAccess(), clientIdIn(allowedClientIds));
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
