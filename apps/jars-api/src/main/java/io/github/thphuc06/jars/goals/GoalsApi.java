package io.github.thphuc06.jars.goals;

import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The goals module as seen by other modules (the advisor's tools): read-only. */
public interface GoalsApi {

    /** A goal as other modules see it. {@code status} is ACTIVE or ACHIEVED. */
    record GoalSummary(UUID id, String name, Money target, LocalDate targetDate, String status) {}

    /** The user's goals that are not archived, oldest first. */
    List<GoalSummary> goals(UserId userId);
}
