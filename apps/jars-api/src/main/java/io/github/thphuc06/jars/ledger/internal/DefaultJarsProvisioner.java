package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.identity.UserRegistered;
import io.github.thphuc06.jars.shared.Ids;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
        int created = ensureFor(event.userId());
        log.info("Default jars for user {}: {} created", event.userId(), created);
    }

    /**
     * Idempotent and race-safe: delivery is at-least-once (a resubmitted event may arrive after an earlier attempt
     * already succeeded) and {@code GET /jars} may run it at the same moment as the listener. Each jar is an
     * {@code INSERT ... ON CONFLICT DO NOTHING}, so concurrent callers all succeed and no jar is duplicated.
     *
     * @return how many jars this call created (0 when they all existed)
     */
    @Transactional
    int ensureFor(UUID userId) {
        int created = 0;
        for (DefaultJars.Spec spec : DefaultJars.SPECS) {
            created += jars.insertIfAbsent(Ids.newId(), userId, spec.code().name(), spec.name(), spec.targetPct());
        }
        return created;
    }
}
