package io.github.thphuc06.jars.identity;

import java.util.UUID;

/**
 * Published once, in the transaction that creates the internal user. From P1.4 the ledger module reacts by creating
 * the default jars and categories (business-rules BR-1).
 */
public record UserRegistered(UUID userId, String email, String locale) {}
