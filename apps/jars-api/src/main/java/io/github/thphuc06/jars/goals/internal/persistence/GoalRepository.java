package io.github.thphuc06.jars.goals.internal.persistence;

import io.github.thphuc06.jars.goals.internal.domain.Goal;
import io.github.thphuc06.jars.goals.internal.domain.GoalStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalRepository extends JpaRepository<Goal, UUID> {

    /**
     * Every lookup of a single goal carries the user id and leaves archived goals out, so someone else's goal, a
     * deleted goal and one that never existed are all just "not found" (anti-IDOR).
     */
    Optional<Goal> findByIdAndUserIdAndStatusNot(UUID id, UUID userId, GoalStatus excluded);

    /** The goals of a user that are not archived, oldest first. */
    List<Goal> findByUserIdAndStatusNotOrderByCreatedAtAscIdAsc(UUID userId, GoalStatus excluded);
}
