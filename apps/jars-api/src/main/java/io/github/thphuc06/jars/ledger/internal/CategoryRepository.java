package io.github.thphuc06.jars.ledger.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CategoryRepository extends JpaRepository<Category, UUID> {

    /** System defaults plus the user's own categories, never another user's. */
    @Query("""
            SELECT c FROM Category c
            WHERE c.userId IS NULL OR c.userId = :userId
            ORDER BY c.kind, c.name
            """)
    List<Category> findVisibleTo(@Param("userId") UUID userId);

    /** One category, only if it is a system default or the user's own; otherwise "not found" (IDOR protection). */
    @Query("SELECT c FROM Category c WHERE c.id = :id AND (c.userId IS NULL OR c.userId = :userId)")
    Optional<Category> findVisible(@Param("id") UUID id, @Param("userId") UUID userId);
}
