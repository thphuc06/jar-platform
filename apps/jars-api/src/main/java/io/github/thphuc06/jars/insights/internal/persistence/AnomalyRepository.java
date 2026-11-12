package io.github.thphuc06.jars.insights.internal.persistence;

import io.github.thphuc06.jars.insights.internal.model.Anomaly;
import io.github.thphuc06.jars.insights.internal.model.AnomalyStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnomalyRepository extends JpaRepository<Anomaly, UUID> {

    Optional<Anomaly> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndStatus(UUID userId, AnomalyStatus status);

    /** First page, newest first. {@code statuses} is never empty (callers pass all statuses for "no filter"). */
    @Query("""
            select a from Anomaly a
            where a.userId = :userId and a.status in :statuses
            order by a.detectedAt desc, a.id desc
            """)
    List<Anomaly> firstPage(
            @Param("userId") UUID userId, @Param("statuses") Collection<AnomalyStatus> statuses, Limit limit);

    /** Keyset: rows strictly after the cursor position {@code (detectedAt, id)} in newest-first order. */
    @Query("""
            select a from Anomaly a
            where a.userId = :userId and a.status in :statuses
              and (a.detectedAt < :at or (a.detectedAt = :at and a.id < :id))
            order by a.detectedAt desc, a.id desc
            """)
    List<Anomaly> pageAfter(
            @Param("userId") UUID userId,
            @Param("statuses") Collection<AnomalyStatus> statuses,
            @Param("at") Instant at,
            @Param("id") UUID id,
            Limit limit);
}
