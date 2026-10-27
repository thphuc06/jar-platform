package io.github.thphuc06.jars.platform.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * jars-api is an OAuth2 resource server (ADR-0010): every /api request carries a Cognito access token.
 * Stateless: no HTTP session, no cookies, therefore no CSRF token is needed.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JarsSecurityProperties.class)
public class SecurityConfig {

    static final String READ = "SCOPE_jars-api/read";
    static final String WRITE = "SCOPE_jars-api/write";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JarsSecurityProperties props) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable()) // bearer tokens are not sent automatically by the browser
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/health")
                        .permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info")
                        .permitAll()
                        // API description + Swagger UI; springdoc serves them only where it is switched on (profile
                        // "local"), everywhere else these paths answer 404
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**")
                        .hasAuthority(READ)
                        .requestMatchers("/api/**")
                        .hasAuthority(WRITE)
                        .anyRequest()
                        .denyAll()) // anything not listed is closed by default
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})
                        // RFC 9728 metadata at /.well-known/oauth-protected-resource: tell clients the truth
                        .protectedResourceMetadata(metadata ->
                                metadata.protectedResourceMetadataCustomizer(m -> m.resourceName("Jars Platform API")
                                        .authorizationServer(props.issuerUri())
                                        .scope("jars-api/read")
                                        .scope("jars-api/write")
                                        .tlsClientCertificateBoundAccessTokens(false))) // no mTLS-bound tokens
                        .authenticationEntryPoint(ProblemDetailSecurityHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(ProblemDetailSecurityHandlers.accessDeniedHandler()));
        return http.build();
    }

    /** Signature is verified with the user pool JWKS (fetched lazily and cached); then the Cognito-specific checks. */
    @Bean
    JwtDecoder jwtDecoder(JarsSecurityProperties props) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withIssuerLocation(props.issuerUri()).build();
        decoder.setJwtValidator(CognitoTokenValidators.forIssuer(props.issuerUri(), props.allowedClientIds()));
        return decoder;
    }
}
