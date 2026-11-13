package io.github.thphuc06.jars.platform.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

    /**
     * No span and no {@code http.server.requests} timer for {@code /actuator/**}: the ALB asks for readiness every 15
     * seconds, which would otherwise be most of the traces of an idle service.
     */
    @Bean
    ObservationPredicate skipActuatorRequests() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext request
                && request.getCarrier().getRequestURI().startsWith("/actuator"));
    }
}
