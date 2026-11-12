package io.github.thphuc06.jars.platform.security;

import jakarta.servlet.DispatcherType;
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
                                "/v3/api-docs.yaml",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**")
                        .permitAll()
                        // An async dispatch continues a request that was already authorized (SSE: the emitter's
                        // completion), an error dispatch renders the error of one. The bearer token filter does not run
                        // on them, so requiring authentication here turned the end of every advisor stream into a 401
                        // body appended to the finished response, which the next request on the keep-alive connection
                        // then read as its own answer (found on AWS, Phase 2 WP6).
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
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
