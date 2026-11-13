package io.github.thphuc06.jars.goals.internal.service;

import io.github.thphuc06.jars.goals.GoalCreated;
import io.github.thphuc06.jars.goals.internal.domain.Goal;
import io.github.thphuc06.jars.goals.internal.domain.GoalProblems;
import io.github.thphuc06.jars.goals.internal.domain.GoalStatus;
import io.github.thphuc06.jars.goals.internal.persistence.GoalRepository;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.platform.audit.Audit;
import io.github.thphuc06.jars.platform.audit.AuditAction;
import io.github.thphuc06.jars.platform.audit.AuditEntry;
import io.github.thphuc06.jars.platform.audit.Audited;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Create, read, change and archive goals. Every lookup is scoped to the caller and an archived goal is invisible, so
 * someone else's goal answers 404 exactly like one that does not exist.
 */
@Service
public class GoalService {

    /** "Today" and every date of a goal are Vietnam time (data-model.md). */
    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    /** A goal as the API shows it. */
    public record GoalView(
            UUID id,
            String name,
            Money targetAmount,
            LocalDate targetDate,
            UUID fundingJarId,
            String status,
            Instant createdAt,
            Instant updatedAt) {
        static GoalView of(Goal g) {
            return new GoalView(
                    g.getId(),
                    g.getName(),
                    g.target(),
                    g.getTargetDate(),
                    g.getFundingJarId(),
                    g.getStatus().name(),
                    g.getCreatedAt(),
                    g.getUpdatedAt());
        }
    }

    public record CreateGoal(String name, Money targetAmount, LocalDate targetDate, UUID fundingJarId) {}

    /** A null field keeps the current value. */
    public record UpdateGoal(
            String name, Money targetAmount, LocalDate targetDate, UUID fundingJarId, GoalStatus status) {}

    private final GoalRepository goals;
    private final LedgerApi ledger;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    GoalService(GoalRepository goals, LedgerApi ledger, ApplicationEventPublisher events, Clock clock) {
        this.goals = goals;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<GoalView> list(UserId userId) {
        return goals.findByUserIdAndStatusNotOrderByCreatedAtAscIdAsc(userId.value(), GoalStatus.ARCHIVED).stream()
                .map(GoalView::of)
                .toList();
    }

    @Transactional
    @Audited(AuditAction.GOAL_CREATED)
    public GoalView create(UserId userId, CreateGoal cmd) {
        if (cmd.fundingJarId() != null) {
            requireOwnJar(userId, cmd.fundingJarId());
        }
        Goal goal = goals.save(Goal.create(
                userId.value(), cmd.name(), cmd.targetAmount(), cmd.targetDate(), cmd.fundingJarId(), today(), now()));
        events.publishEvent(new GoalCreated(Uuids.v7(), goal.getId(), userId, goal.target(), goal.getTargetDate()));
        // the name is free text the user chose: not recorded
        AuditEntry entry =
                Audit.entity(goal.getId()).with("targetAmount", goal.target()).with("targetDate", goal.getTargetDate());
        if (goal.getFundingJarId() != null) {
            entry.with("fundingJarId", goal.getFundingJarId());
        }
        return GoalView.of(goal);
    }

    @Transactional
    @Audited(AuditAction.GOAL_UPDATED)
    public GoalView update(UserId userId, UUID goalId, UpdateGoal cmd) {
        Goal goal = require(userId, goalId);
        Instant now = now();
        List<String> changed = new ArrayList<>();
        if (cmd.fundingJarId() != null) {
            requireOwnJar(userId, cmd.fundingJarId());
        }
        if (cmd.name() != null) {
            goal.rename(cmd.name(), now);
            changed.add("name");
        }
        if (cmd.targetAmount() != null) {
            goal.changeTarget(cmd.targetAmount(), now);
            changed.add("targetAmount");
        }
        if (cmd.targetDate() != null) {
            goal.changeTargetDate(cmd.targetDate(), today(), now);
            changed.add("targetDate");
        }
        if (cmd.fundingJarId() != null) {
            goal.fundWith(cmd.fundingJarId(), now);
            changed.add("fundingJarId");
        }
        if (cmd.status() != null) {
            changed.add("status");
            switch (cmd.status()) {
                case ACHIEVED -> goal.markAchieved(now);
                case ACTIVE -> goal.reactivate(now);
                case ARCHIVED ->
                    throw new DomainException(
                            GoalProblems.INVALID_GOAL, "Dùng DELETE /api/v1/goals/{id} để lưu trữ mục tiêu.");
            }
        }
        // which fields changed and where the goal ended up, not the new name (free text)
        Audit.entity(goal.getId())
                .withCodes("changed", changed)
                .with("targetAmount", goal.target())
                .with("status", goal.getStatus());
        return GoalView.of(goal);
    }

    @Transactional
    @Audited(AuditAction.GOAL_ARCHIVED)
    public void archive(UserId userId, UUID goalId) {
        require(userId, goalId).archive(now());
        Audit.entity(goalId);
    }

    /** The caller's goal that is not archived, or 404. */
    @Transactional(readOnly = true)
    public Goal require(UserId userId, UUID goalId) {
        return goals.findByIdAndUserIdAndStatusNot(goalId, userId.value(), GoalStatus.ARCHIVED)
                .orElseThrow(() -> DomainException.notFound("Mục tiêu"));
    }

    /** A jar of someone else, an archived jar and one that does not exist are all the same 404. */
    private void requireOwnJar(UserId userId, UUID jarId) {
        if (ledger.jars(userId).stream().noneMatch(j -> j.id().equals(jarId))) {
            throw DomainException.notFound("Hũ");
        }
    }

    LocalDate today() {
        return clock.instant().atZone(VIETNAM).toLocalDate();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
