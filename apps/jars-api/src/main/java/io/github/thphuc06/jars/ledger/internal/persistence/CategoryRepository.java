package io.github.thphuc06.jars.ledger.internal.persistence;

import io.github.thphuc06.jars.ledger.internal.domain.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    /** System categories plus the user's own. */
    @Query("select c from Category c where c.userId is null or c.userId = :userId order by c.kind, c.name")
    List<Category> findVisibleTo(@Param("userId") UUID userId);

    @Query("select c from Category c where c.id = :id and (c.userId is null or c.userId = :userId)")
    Optional<Category> findVisibleById(@Param("id") UUID id, @Param("userId") UUID userId);
}
