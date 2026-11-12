package io.github.thphuc06.jars.platform.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.util.StringUtils;

/**
 * Decoder for Cognito tokens. Metadata (JWKS location) is fetched on the first token, not at startup, so the app
 * boots offline and in tests. Signatures are then verified locally against the cached JWKS.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
class JwtDecoderConfig {

    @Bean
    @ConditionalOnProperty(name = "jars.security.local-dev-tokens", havingValue = "false", matchIfMissing = true)
    JwtDecoder cognitoJwtDecoder(SecurityProperties properties) {
        String issuer = properties.issuerUri();
        if (!StringUtils.hasText(issuer)) {
            return token -> {
                throw new BadJwtException("No identity provider configured (jars.security.issuer-uri is empty)");
            };
        }
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder =
                    NimbusJwtDecoder.withIssuerLocation(issuer).build();
            decoder.setJwtValidator(AccessTokenValidators.forIssuer(issuer, properties.allowedClientIds()));
            return decoder;
        });
    }
}
