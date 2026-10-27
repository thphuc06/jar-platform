package io.github.thphuc06.jars.ledger.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Inserts only: entries are append-only (Flyway V4 trigger), so there is nothing to update or delete. */
interface JarEntryRepository extends JpaRepository<JarEntry, UUID> {}
