package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.identity.UserRegistered;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Creates the default jars of a new user (BR-1). First cross-module interaction of the system: identity does not
 * know the ledger exists, it only publishes {@link UserRegistered}.
 */
@Component
class DefaultJarsProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DefaultJarsProvisioner.class);

    private final JarRepository jars;

    DefaultJarsProvisioner(JarRepository jars) {
        this.jars = jars;
    }

    /**
     * {@code @ApplicationModuleListener} = after the publisher's commit, asynchronously, in a NEW transaction. The
     * outbox row is deleted only if this method returns normally; if it throws, the row stays as FAILED and the event
     * can be resubmitted. The fixed {@code id} keeps stored publications valid if the class or method is renamed.
     */
    @ApplicationModuleListener(id = "ledger.default-jars")
    void on(UserRegistered event) {
        // delivery is at-least-once: a resubmitted publication may arrive after an earlier attempt already succeeded
        if (jars.existsByUserId(event.userId())) {
            log.info("Default jars already exist for user {}, nothing to do", event.userId());
            return;
        }
        jars.saveAll(DefaultJars.openFor(event.userId()));
        log.info("Created {} default jars for user {}", DefaultJars.SPECS.size(), event.userId());
    }
}
