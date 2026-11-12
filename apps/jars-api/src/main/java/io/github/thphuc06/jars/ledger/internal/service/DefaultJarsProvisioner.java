package io.github.thphuc06.jars.ledger.internal.service;

import io.github.thphuc06.jars.identity.UserRegistered;
import io.github.thphuc06.jars.ledger.JarCreated;
import io.github.thphuc06.jars.ledger.internal.domain.Jar;
import io.github.thphuc06.jars.ledger.internal.domain.JarCode;
import io.github.thphuc06.jars.ledger.internal.persistence.JarRepository;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.Uuids;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Gives every new user the six default jars. A plain synchronous listener on purpose: it runs inside the transaction
 * that creates the user, so the user and the jars are committed together or not at all. With an asynchronous listener
 * a failure would leave a user without jars.
 */
@Component
class DefaultJarsProvisioner {

    private final JarRepository jars;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DefaultJarsProvisioner(JarRepository jars, ApplicationEventPublisher events, Clock clock) {
        this.jars = jars;
        this.events = events;
        this.clock = clock;
    }

    @EventListener
    void on(UserRegistered event) {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (JarCode code : JarCode.defaults()) {
            Jar jar = jars.save(
                    new Jar(event.userId().value(), code, code.displayName(), code.defaultPct(), Money.VND, now));
            events.publishEvent(new JarCreated(Uuids.v7(), jar.getId(), event.userId(), code.name()));
        }
    }
}
