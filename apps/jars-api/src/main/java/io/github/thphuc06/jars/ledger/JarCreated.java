package io.github.thphuc06.jars.ledger;

import io.github.thphuc06.jars.shared.UserId;
import java.util.UUID;

/** A jar now exists (a default jar of a new user or a custom one). {@code eventId} is unique per publication. */
public record JarCreated(UUID eventId, UUID jarId, UserId userId, String code) {}
