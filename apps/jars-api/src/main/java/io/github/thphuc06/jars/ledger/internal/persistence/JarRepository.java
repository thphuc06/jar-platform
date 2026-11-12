package io.github.thphuc06.jars.ledger.internal.persistence;

import io.github.thphuc06.jars.ledger.internal.domain.Jar;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JarRepository extends JpaRepository<Jar, UUID> {

    /** Every lookup of a single jar carries the user id, so someone else's jar is simply "not found" (anti-IDOR). */
    Optional<Jar> findByIdAndUserId(UUID id, UUID userId);

    List<Jar> findByUserIdAndArchivedAtIsNullOrderByCreatedAtAscIdAsc(UUID userId);

    List<Jar> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    boolean existsByUserId(UUID userId);

    @Query("select coalesce(sum(j.targetPct), 0) from Jar j where j.userId = :userId and j.archivedAt is null")
    BigDecimal sumActiveTargetPct(@Param("userId") UUID userId);

    /**
     * Serialises "does the budget still add up to 100%?" checks of one user for the rest of the transaction. Jars
     * have a version column, but that only protects a single jar; the sum over several jars needs this lock.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) AS locked", nativeQuery = true)
    int lockBudgetOf(@Param("key") long key);
}
