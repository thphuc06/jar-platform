package io.github.thphuc06.jars.platform.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Cognito settings (non-secret: every SPA knows them). Values per environment live in application-{profile}.yaml.
 *
 * @param issuerUri Cognito user pool issuer, e.g. https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_xxx
 * @param allowedClientIds app clients whose access tokens the API accepts (claim {@code client_id})
 * @param userInfoUri OIDC userInfo endpoint of the managed login domain (used once per user to read the e-mail)
 */
@Validated
@ConfigurationProperties("jars.security")
public record JarsSecurityProperties(
        @NotBlank String issuerUri,
        @NotEmpty List<String> allowedClientIds,
        @NotBlank String userInfoUri) {}
