/**
 * Source of truth for money: jars and their balances, categories, and (from P1.5) transactions and the append-only
 * jar ledger. Reacts to {@link io.github.thphuc06.jars.identity.UserRegistered} by creating the default jars.
 *
 * <p>{@code allowedDependencies} turns the arrow "ledger → identity" of docs/02-architecture/modules.md §3 into a
 * rule that {@code ModularityTests} enforces: depending on any other business module fails the build.
 */
@ApplicationModule(
        displayName = "Ledger",
        allowedDependencies = {"identity", "shared"})
package io.github.thphuc06.jars.ledger;

import org.springframework.modulith.ApplicationModule;
