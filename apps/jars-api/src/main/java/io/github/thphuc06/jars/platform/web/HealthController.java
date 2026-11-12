package io.github.thphuc06.jars.platform.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated liveness check for clients and smoke tests (reached through CloudFront at /api/*).
 * Infrastructure probes (ALB, ECS) use /actuator/health/readiness instead.
 */
@RestController
@RequestMapping("/api/v1")
class HealthController {

    private final String version;

    HealthController(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "dev";
    }

    @GetMapping("/health")
    HealthResponse health() {
        return new HealthResponse("UP", "jars-api", version);
    }

    record HealthResponse(String status, String service, String version) {}
}
