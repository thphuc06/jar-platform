package io.github.thphuc06.jars.goals.internal.domain;

import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A savings goal: an amount to reach by a date, optionally funded by one jar. The rules of what a valid goal is live
 * here; whether the funding jar exists and belongs to the user is checked by the service, which can ask the ledger.
 */
@Entity
@Table(name = "goals")
public class Goal {

    static final int MAX_NAME_LENGTH = 80;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "target_amount", nullable = false)
    private BigDecimal targetAmount;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "funding_jar_id")
    private UUID fundingJarId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private GoalStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Goal() {} // for JPA

    private Goal(UUID userId, String name, Money target, LocalDate targetDate, UUID fundingJarId, Instant now) {
        this.userId = userId;
        this.name = name;
        this.targetAmount = target.amount();
        this.currency = target.currency().getCurrencyCode();
        this.targetDate = targetDate;
        this.fundingJarId = fundingJarId;
        this.status = GoalStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * @param today the current date in Vietnam time; the target date must be after it
     * @throws DomainException 422 for a blank or over-long name, an amount that is not positive, or a date that is not
     *     in the future
     */
    public static Goal create(
            UUID userId,
            String name,
            Money target,
            LocalDate targetDate,
            UUID fundingJarId,
            LocalDate today,
            Instant now) {
        String cleanName = requireName(name);
        requirePositive(target);
        requireFuture(targetDate, today);
        return new Goal(userId, cleanName, target, targetDate, fundingJarId, now);
    }

    public void rename(String newName, Instant now) {
        this.name = requireName(newName);
        this.updatedAt = now;
    }

    public void changeTarget(Money newTarget, Instant now) {
        requirePositive(newTarget);
        this.targetAmount = newTarget.amount();
        this.updatedAt = now;
    }

    public void changeTargetDate(LocalDate newDate, LocalDate today, Instant now) {
        requireFuture(newDate, today);
        this.targetDate = newDate;
        this.updatedAt = now;
    }

    public void fundWith(UUID jarId, Instant now) {
        this.fundingJarId = jarId;
        this.updatedAt = now;
    }

    /** The user says the target is reached. Marking an achieved goal again changes nothing. */
    public void markAchieved(Instant now) {
        if (status == GoalStatus.ACTIVE) {
            this.status = GoalStatus.ACHIEVED;
            this.updatedAt = now;
        }
    }

    /** Back to being saved for, for example after raising the target. */
    public void reactivate(Instant now) {
        if (status == GoalStatus.ACHIEVED) {
            this.status = GoalStatus.ACTIVE;
            this.updatedAt = now;
        }
    }

    /** Removes the goal from the view of the user; archiving twice changes nothing. */
    public void archive(Instant now) {
        if (status != GoalStatus.ARCHIVED) {
            this.status = GoalStatus.ARCHIVED;
            this.archivedAt = now;
            this.updatedAt = now;
        }
    }

    public boolean isArchived() {
        return status == GoalStatus.ARCHIVED;
    }

    public Money target() {
        return new Money(targetAmount, Currency.getInstance(currency));
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw invalid("Tên mục tiêu không được để trống.");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw invalid("Tên mục tiêu tối đa " + MAX_NAME_LENGTH + " ký tự.");
        }
        return trimmed;
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw invalid("Số tiền mục tiêu phải lớn hơn 0.");
        }
    }

    private static void requireFuture(LocalDate date, LocalDate today) {
        if (date == null || !date.isAfter(today)) {
            throw invalid("Ngày đạt mục tiêu phải sau hôm nay.");
        }
    }

    private static DomainException invalid(String detail) {
        return new DomainException(GoalProblems.INVALID_GOAL, detail);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public UUID getFundingJarId() {
        return fundingJarId;
    }

    public GoalStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
