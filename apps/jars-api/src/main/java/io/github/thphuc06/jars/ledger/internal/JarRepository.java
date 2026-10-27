package io.github.thphuc06.jars.ledger.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface JarRepository extends JpaRepository<Jar, UUID> {

    boolean existsByUserId(UUID userId);

    List<Jar> findByUserId(UUID userId);

    List<Jar> findByUserIdAndArchivedAtIsNull(UUID userId);

    /** The only way to load a jar by id: a jar of another user is "not found" (IDOR protection). */
    Optional<Jar> findByIdAndUserId(UUID id, UUID userId);

    Optional<Jar> findByUserIdAndCode(UUID userId, JarCode code);

    /**
     * Race-safe "create if missing" for a standard jar: when two callers create the default jars at the same time
     * (the {@code UserRegistered} listener and a first {@code GET /jars}), both succeed and only one row exists. The
     * {@code WHERE} repeats the predicate of the partial index {@code uk_jars_user_code}, otherwise PostgreSQL cannot
     * use it as the conflict target.
     *
     * @return 1 if this call inserted the jar, 0 if it already existed
     */
    @Modifying
    @Query(value = """
                    INSERT INTO jars (id, user_id, code, name, target_pct)
                    VALUES (:id, :userId, :code, :name, CAST(:targetPct AS NUMERIC(5, 2)))
                    ON CONFLICT (user_id, code) WHERE code <> 'CUSTOM' DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("code") String code,
            @Param("name") String name,
            @Param("targetPct") BigDecimal targetPct);
}
