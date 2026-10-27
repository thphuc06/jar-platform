package io.github.thphuc06.jars.goals.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface GoalRepository extends JpaRepository<Goal, UUID> {

    /** Nearest deadline first. */
    List<Goal> findByUserIdAndStatusNotOrderByTargetDateAscIdAsc(UUID userId, GoalStatus excluded);

    /** A goal of another user is "not found" (IDOR protection). */
    Optional<Goal> findByIdAndUserId(UUID id, UUID userId);
}
