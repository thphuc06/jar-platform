/**
 * Savings goals (business-rules BR-6): CRUD in Phase 1; feasibility, what-if and roadmap in Phase 2 (P2.7). A goal
 * may name a funding jar; the jar stays owned by the ledger and is read through {@link
 * io.github.thphuc06.jars.ledger.LedgerApi} only.
 */
@ApplicationModule(
        displayName = "Goals",
        allowedDependencies = {"identity", "ledger", "shared"})
package io.github.thphuc06.jars.goals;

import org.springframework.modulith.ApplicationModule;
