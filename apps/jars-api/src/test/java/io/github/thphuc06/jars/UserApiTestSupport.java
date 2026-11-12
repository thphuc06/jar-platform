package io.github.thphuc06.jars;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.platform.security.UserDirectory;
import io.github.thphuc06.jars.shared.UserId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/**
 * {@link ApiTestSupport} plus access to the internal id of a test user, for tests that call a module's Java API or a
 * service directly instead of going through HTTP.
 */
public abstract class UserApiTestSupport extends ApiTestSupport {

    /** A caller as the HTTP layer sees it (the token) and as the modules see it (the id). */
    public record TestUser(JwtRequestPostProcessor token, UserId id) {}

    @Autowired
    protected UserDirectory userDirectory;

    /** A new user with the six default jars already provisioned. */
    protected TestUser newTestUser() {
        String sub = "test-" + UUID.randomUUID();
        return new TestUser(jwt().jwt(j -> j.subject(sub)), userDirectory.resolve(sub));
    }
}
