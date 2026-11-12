package io.github.thphuc06.jars.platform.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local development without Cognito. When {@code jars.security.local-dev-tokens=true} (only ever set in
 * {@code application-local.yaml}) the application mints its own access tokens, signed with a throw-away RSA key created
 * at startup, and accepts them. Both beans exist <em>only</em> in the {@code local} profile: the {@code aws} profile
 * has neither the endpoint nor the decoder, so a deployed instance can only verify Cognito tokens.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "jars.security.local-dev-tokens", havingValue = "true")
class LocalDevTokenConfig {

    static final String ISSUER = "http://localhost/dev";
    static final String CLIENT_ID = "local-dev";

    @Bean
    RSAKey localDevKey() throws Exception {
        return new RSAKeyGenerator(2048).keyID("local-dev").generate();
    }

    @Bean
    JwtDecoder localDevJwtDecoder(RSAKey key) throws Exception {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(AccessTokenValidators.forIssuer(ISSUER, java.util.List.of(CLIENT_ID)));
        return decoder;
    }

    @Bean
    JwtEncoder localDevJwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
    }

    // a nested class is component-scanned on its own, whatever the outer class's conditions say: repeat them,
    // otherwise the aws profile would try to create this controller and fail without a JwtEncoder
    @RestController
    @Profile("local")
    @ConditionalOnProperty(name = "jars.security.local-dev-tokens", havingValue = "true")
    static class TokenController {

        private static final long LIFETIME_SECONDS = 3600;

        private final JwtEncoder encoder;

        TokenController(JwtEncoder encoder) {
            this.encoder = encoder;
        }

        record TokenRequest(String sub) {}

        /** {@code POST /dev/token {"sub":"demo"}} returns an access token for that subject (the user is created on first use). */
        @PostMapping("/dev/token")
        ResponseEntity<Map<String, Object>> token(@RequestBody TokenRequest request) {
            String sub = request.sub() == null || request.sub().isBlank()
                    ? "demo"
                    : request.sub().trim();
            Instant now = Instant.now();
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(ISSUER)
                    .subject(sub)
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(LIFETIME_SECONDS))
                    .claim("token_use", "access")
                    .claim("client_id", CLIENT_ID)
                    .build();
            String value = encoder.encode(JwtEncoderParameters.from(
                            JwsHeader.with(SignatureAlgorithm.RS256)
                                    .keyId("local-dev")
                                    .build(),
                            claims))
                    .getTokenValue();
            return ResponseEntity.ok(Map.of("accessToken", value, "expiresIn", LIFETIME_SECONDS));
        }
    }
}
