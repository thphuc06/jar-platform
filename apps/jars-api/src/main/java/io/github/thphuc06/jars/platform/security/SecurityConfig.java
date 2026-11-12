package io.github.thphuc06.jars.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless resource server: a valid Cognito access token is required everywhere except health and API docs.
 * There is no CSRF protection because there are no cookies, and no CORS because the UI is served from the same
 * CloudFront origin (Vite proxies {@code /api} locally). Authorization is by data ownership, not by scope.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper) throws Exception {
        ProblemResponseWriter problems = new ProblemResponseWriter(jsonMapper);
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/v1/health",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/dev/token",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problems::unauthenticated)
                        .accessDeniedHandler(problems::forbidden))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(problems::unauthenticated)
                        .accessDeniedHandler(problems::forbidden));
        return http.build();
    }
}
