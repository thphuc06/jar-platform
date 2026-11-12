package io.github.thphuc06.jars.ledger.internal.persistence;

import io.github.thphuc06.jars.ledger.internal.domain.LedgerTransaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByIdAndUserId(UUID id, UUID userId);
}
