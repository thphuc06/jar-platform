package io.github.thphuc06.jars.identity.internal;

import io.github.thphuc06.jars.identity.UserRegistered;
import io.github.thphuc06.jars.shared.Ids;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the internal user. A separate bean on purpose: {@code @Transactional} works through a proxy, so it is only
 * applied when the method is called from ANOTHER bean (a call from within the same class would skip the proxy).
 */
@Component
class UserRegistrar {

    static final String DEFAULT_LOCALE = "vi-VN";

    private final UserRepository users;
    private final ApplicationEventPublisher events;

    UserRegistrar(UserRepository users, ApplicationEventPublisher events) {
        this.users = users;
        this.events = events;
    }

    @Transactional
    public User register(String cognitoSub, String email) {
        UUID newId = Ids.newId();
        int inserted = users.insertIfAbsent(newId, cognitoSub, email.trim(), DEFAULT_LOCALE);
        if (inserted == 1) {
            // published inside the transaction; listeners added in P1.4 run only after commit
            events.publishEvent(new UserRegistered(newId, email.trim(), DEFAULT_LOCALE));
        }
        // under READ COMMITTED this new statement sees the row committed by a concurrent first request
        return users.findByCognitoSub(cognitoSub)
                .orElseThrow(() -> new IllegalStateException("user row missing after insert for sub " + cognitoSub));
    }
}
