package io.github.thphuc06.jars.ledger.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {}
