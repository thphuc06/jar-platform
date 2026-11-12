package io.github.thphuc06.jars.insights.internal.persistence;

import io.github.thphuc06.jars.insights.internal.model.DetectorStateEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DetectorStateRepository extends JpaRepository<DetectorStateEntity, UUID> {

    /**
     * Locks the jar's state row until the transaction ends, so concurrent events of one jar are processed one after
     * another (the CUSUM recursion depends on seeing every expense exactly once and in sequence).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DetectorStateEntity s where s.jarId = :jarId")
    Optional<DetectorStateEntity> lockByJarId(@Param("jarId") UUID jarId);

    @Modifying
    @Query(value = """
                    INSERT INTO anomaly_detector_state (jar_id, user_id, updated_at)
                    VALUES (:jarId, :userId, now())
                    ON CONFLICT (jar_id) DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(@Param("jarId") UUID jarId, @Param("userId") UUID userId);
}
