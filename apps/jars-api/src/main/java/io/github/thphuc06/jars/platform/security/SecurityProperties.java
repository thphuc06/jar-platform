package io.github.thphuc06.jars.platform.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code jars.security}.
 *
 * @param issuerUri issuer of the Cognito user pool; blank means "no identity provider configured" and every token
 *     is rejected (tests use {@code jwt()} and never reach the decoder)
 * @param allowedClientIds Cognito app clients whose access tokens are accepted (Cognito access tokens have no
 *     {@code aud}, only {@code client_id})
 * @param localDevTokens local profile only: accept tokens minted by {@code /dev/token} instead of Cognito
 */
@ConfigurationProperties("jars.security")
public record SecurityProperties(String issuerUri, List<String> allowedClientIds, boolean localDevTokens) {

    public SecurityProperties {
        allowedClientIds = allowedClientIds == null ? List.of() : List.copyOf(allowedClientIds);
    }
}
