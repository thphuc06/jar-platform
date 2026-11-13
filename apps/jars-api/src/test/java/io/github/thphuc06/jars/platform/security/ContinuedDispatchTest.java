package io.github.thphuc06.jars.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.ApiTestSupport;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * An async or error dispatch continues a request that was already authorized; the bearer token filter does not run
 * on it. Requiring authentication there made the end of every SSE stream append a 401 body to the finished response
 * (seen on AWS through CloudFront in Phase 2 WP6). A request dispatch without a token is still a 401.
 */
class ContinuedDispatchTest extends ApiTestSupport {

    @Test
    void anAsyncOrErrorDispatchIsNotAskedForATokenAgain() {
        for (DispatcherType type : new DispatcherType[] {DispatcherType.ASYNC, DispatcherType.ERROR}) {
            int status = mvc.perform(MockMvcRequestBuilders.get("/api/v1/no-such-endpoint")
                            .with(request -> {
                                request.setDispatcherType(type);
                                return request;
                            }))
                    .getResponse()
                    .getStatus();
            assertThat(status).as(type.name()).isNotEqualTo(401);
        }
    }

    @Test
    void aFreshRequestWithoutATokenIsStill401() {
        assertThat(get(null, "/api/v1/me").status()).isEqualTo(401);
    }
}
