package io.github.thphuc06.jars.ledger.internal.persistence;

import io.github.thphuc06.jars.ledger.internal.domain.JarEntry;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JarEntryRepository extends JpaRepository<JarEntry, UUID> {

    /** The audit figure: a jar's cached balance must always equal this. */
    @Query("select coalesce(sum(e.amount), 0) from JarEntry e where e.jarId = :jarId")
    BigDecimal sumByJarId(@Param("jarId") UUID jarId);

    long countByJarId(UUID jarId);
}
