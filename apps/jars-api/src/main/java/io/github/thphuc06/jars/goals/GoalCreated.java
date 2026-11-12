package io.github.thphuc06.jars.goals;

import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A savings goal was created. Published inside the transaction that creates it. Nobody listens yet (the catalog keeps it
 * for the advisor); {@code eventId} is unique per publication.
 */
public record GoalCreated(UUID eventId, UUID goalId, UserId userId, Money targetAmount, LocalDate targetDate) {}
