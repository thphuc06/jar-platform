package io.github.thphuc06.jars.identity;

import io.github.thphuc06.jars.shared.UserId;

/**
 * A user was created, on their first authenticated request (flow F1). Published inside the transaction that inserts the
 * user, so listeners that must succeed together with it (the {@code ledger} creates the default jars) run
 * synchronously in that same transaction.
 */
public record UserRegistered(UserId userId, String locale) {}
