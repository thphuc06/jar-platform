package io.github.thphuc06.jars.identity.internal;

import io.github.thphuc06.jars.identity.UserRegistered;
import io.github.thphuc06.jars.shared.ConflictException;
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
            // written to the outbox in THIS transaction; listeners run only after commit (rolled back = never sent)
            events.publishEvent(new UserRegistered(newId, DEFAULT_LOCALE));
        }
        // under READ COMMITTED this new statement sees the row committed by a concurrent first request. Nothing
        // inserted and still no row for this sub: the e-mail belongs to ANOTHER account (e.g. deleted and
        // re-created in Cognito with a new sub)
        return users.findByCognitoSub(cognitoSub)
                .orElseThrow(() -> new ConflictException(
                        "email-already-registered", "This e-mail is already linked to another account"));
    }
}
