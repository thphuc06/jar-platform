package io.github.thphuc06.jars.ledger.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    /** The only way to load a transaction for a change: one of another user is "not found" (IDOR protection). */
    Optional<LedgerTransaction> findByIdAndUserId(UUID id, UUID userId);
}
