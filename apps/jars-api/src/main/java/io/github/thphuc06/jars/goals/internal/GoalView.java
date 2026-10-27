package io.github.thphuc06.jars.goals.internal;

import io.github.thphuc06.jars.ledger.JarSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A goal for the web layer, with the current state of its funding jar (read from the ledger). */
public record GoalView(
        UUID id,
        String name,
        BigDecimal targetAmount,
        LocalDate targetDate,
        GoalStatus status,
        @Nullable JarSummary fundingJar,
        @Nullable Integer progressPct) {

    static GoalView of(Goal goal, @Nullable JarSummary fundingJar) {
        return new GoalView(
                goal.id(),
                goal.name(),
                goal.targetAmount(),
                goal.targetDate(),
                goal.status(),
                fundingJar,
                fundingJar == null ? null : progressPct(fundingJar.balance(), goal.targetAmount()));
    }

    /**
     * BR-6: {@code floor(balance × 100 / target)}, at most 100. Rounded DOWN, so 99.99 % shows 99 and the goal never
     * looks reached before the money is really there.
     */
    static int progressPct(BigDecimal balance, BigDecimal target) {
        int pct = balance.multiply(BigDecimal.valueOf(100))
                .divide(target, 0, RoundingMode.DOWN)
                .intValueExact();
        return Math.min(pct, 100);
    }
}
