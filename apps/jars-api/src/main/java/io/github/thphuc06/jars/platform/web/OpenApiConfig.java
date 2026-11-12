package io.github.thphuc06.jars.platform.web;

import io.github.thphuc06.jars.shared.UserId;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API description. Controllers receive the caller as a {@link UserId} argument that is resolved from the token, so
 * it must not show up as a request parameter; every endpoint except health needs a Cognito access token.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(UserId.class);
    }

    @Bean
    OpenAPI jarsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Jars Platform API")
                        .version("v1")
                        .description("Personal finance with the 6-jars method. Money amounts are strings with two"
                                + " decimals; every POST that changes money needs an Idempotency-Key header;"
                                + " errors are RFC 9457 problem+json."))
                .servers(java.util.List.of(new Server().url("/")))
                .components(new Components()
                        .addSecuritySchemes(
                                "cognito",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Cognito access token (not the ID token)")))
                .addSecurityItem(new SecurityRequirement().addList("cognito"));
    }
}
