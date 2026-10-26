package io.github.thphuc06.jars.identity;

import java.util.UUID;

/**
 * Published once, in the transaction that creates the internal user. The ledger module reacts by creating the default
 * jars (business-rules BR-1).
 *
 * <p>Deliberately carries no e-mail: the event is stored in the outbox table ({@code event_publication}), so personal
 * data in it would be copied into a second table. A listener that needs more asks {@link IdentityApi}.
 */
public record UserRegistered(UUID userId, String locale) {}
