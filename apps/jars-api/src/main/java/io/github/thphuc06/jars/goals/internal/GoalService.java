package io.github.thphuc06.jars.goals.internal;

import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.shared.BusinessRuleViolationException;
import io.github.thphuc06.jars.shared.BusinessTime;
import io.github.thphuc06.jars.shared.NotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Goals CRUD (business-rules BR-6). Jars are read through {@link LedgerApi}, never from ledger tables. */
@Service
public class GoalService {

    private final GoalRepository goals;
    private final LedgerApi ledger;
    private final Clock clock;

    GoalService(GoalRepository goals, LedgerApi ledger, Clock clock) {
        this.goals = goals;
        this.ledger = ledger;
        this.clock = clock;
    }

    public record NewGoal(
            String name,
            BigDecimal targetAmount,
            LocalDate targetDate,
            @Nullable UUID fundingJarId) {}

    /**
     * PATCH semantics: {@code null} = unchanged. {@code fundingJarId} is the only field that can be cleared, so it
     * is an {@code Optional}: {@code null} = unchanged, empty = detach the jar, value = new jar.
     */
    public record GoalChanges(
            @Nullable String name,
            @Nullable BigDecimal targetAmount,
            @Nullable LocalDate targetDate,
            @Nullable Optional<UUID> fundingJarId,
            @Nullable GoalStatus status) {}

    /**
     * The jars come in ONE call for the whole list and are joined in memory: asking the ledger once per goal would be
     * an N+1 across module boundaries.
     */
    @Transactional(readOnly = true)
    public List<GoalView> list(UUID userId) {
        List<Goal> found = goals.findByUserIdAndStatusNotOrderByTargetDateAscIdAsc(userId, GoalStatus.ARCHIVED);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, JarSummary> jarsById =
                ledger.jars(userId).stream().collect(Collectors.toMap(JarSummary::id, Function.identity()));
        return found.stream()
                .map(goal -> GoalView.of(goal, goal.fundingJarId() == null ? null : jarsById.get(goal.fundingJarId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public GoalView get(UUID userId, UUID goalId) {
        return view(userId, visibleGoal(userId, goalId));
    }

    @Transactional
    public GoalView create(UUID userId, NewGoal goal) {
        requireFuture(goal.targetDate());
        if (goal.fundingJarId() != null) {
            usableFundingJar(userId, goal.fundingJarId());
        }
        Goal saved = goals.save(
                Goal.create(userId, goal.name(), goal.targetAmount(), goal.targetDate(), goal.fundingJarId()));
        return view(userId, saved);
    }

    /** A concurrent update of the same goal fails on the version check (409 concurrent-modification). */
    @Transactional
    public GoalView update(UUID userId, UUID goalId, GoalChanges changes) {
        Goal goal = visibleGoal(userId, goalId);
        if (changes.name() != null) {
            goal.rename(changes.name());
        }
        if (changes.targetAmount() != null) {
            goal.changeTarget(changes.targetAmount());
        }
        if (changes.targetDate() != null && !changes.targetDate().equals(goal.targetDate())) {
            requireFuture(changes.targetDate());
            goal.reschedule(changes.targetDate());
        }
        if (changes.fundingJarId() != null) {
            UUID jarId = changes.fundingJarId().orElse(null);
            if (jarId != null) {
                usableFundingJar(userId, jarId);
            }
            goal.fundFrom(jarId);
        }
        if (changes.status() != null) {
            if (changes.status() == GoalStatus.ARCHIVED) {
                throw new BusinessRuleViolationException("use-delete-to-archive", "Archive a goal with DELETE");
            }
            goal.markAs(changes.status());
        }
        goals.flush(); // the version-checked UPDATE runs here, inside the transaction
        return view(userId, goal);
    }

    /** Soft delete; deleting an already archived goal is a no-op, so DELETE stays idempotent. */
    @Transactional
    public void archive(UUID userId, UUID goalId) {
        goals.findByIdAndUserId(goalId, userId)
                .orElseThrow(() -> new NotFoundException("goal", goalId))
                .archive();
    }

    private Goal visibleGoal(UUID userId, UUID goalId) {
        return goals.findByIdAndUserId(goalId, userId)
                .filter(goal -> !goal.isArchived())
                .orElseThrow(() -> new NotFoundException("goal", goalId));
    }

    private GoalView view(UUID userId, Goal goal) {
        JarSummary jar = goal.fundingJarId() == null
                ? null
                : ledger.jar(userId, goal.fundingJarId()).orElse(null);
        return GoalView.of(goal, jar);
    }

    /** BR-6: "today" is the Vietnamese date, the server clock is UTC. */
    private void requireFuture(LocalDate targetDate) {
        if (!targetDate.isAfter(BusinessTime.today(clock))) {
            throw new BusinessRuleViolationException(
                    "target-date-not-in-future", "targetDate must be after today (Vietnam time)");
        }
    }

    /** BR-6: an own jar, not archived, not POOL. Someone else's jar is "not found". */
    private void usableFundingJar(UUID userId, UUID jarId) {
        JarSummary jar = ledger.jar(userId, jarId).orElseThrow(() -> new NotFoundException("jar", jarId));
        if (jar.isPool()) {
            throw new BusinessRuleViolationException(
                    "funding-jar-is-pool", "Unallocated money (POOL) cannot fund a goal; pick a jar");
        }
        if (jar.archived()) {
            throw new BusinessRuleViolationException("funding-jar-archived", "The funding jar is archived");
        }
    }
}
