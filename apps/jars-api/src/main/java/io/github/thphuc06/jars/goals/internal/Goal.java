package io.github.thphuc06.jars.goals.internal;

import io.github.thphuc06.jars.shared.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * Row of table {@code goals} (Flyway V8). {@code fundingJarId} is a plain id, not an association: the jar belongs to
 * the ledger module (no cross-module JPA relations, docs/02-architecture/modules.md rule 3).
 */
@Entity
@Table(name = "goals")
class Goal {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Column(name = "target_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal targetAmount;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "funding_jar_id")
    private UUID fundingJarId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoalStatus status;

    /** Optimistic lock; null until persisted, which is how Spring Data knows the goal is new. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Goal() {} // for JPA

    private Goal(UUID userId, String name, BigDecimal targetAmount, LocalDate targetDate, @Nullable UUID fundingJarId) {
        this.id = Ids.newId();
        this.userId = userId;
        this.name = name;
        this.targetAmount = targetAmount;
        this.targetDate = targetDate;
        this.fundingJarId = fundingJarId;
        this.status = GoalStatus.ACTIVE;
    }

    /** The caller has checked the target date and the funding jar (they need the clock and the ledger). */
    static Goal create(
            UUID userId, String name, BigDecimal targetAmount, LocalDate targetDate, @Nullable UUID fundingJarId) {
        return new Goal(userId, name.strip(), scaled(targetAmount), targetDate, fundingJarId);
    }

    void rename(String newName) {
        this.name = newName.strip();
    }

    void changeTarget(BigDecimal newAmount) {
        this.targetAmount = scaled(newAmount);
    }

    void reschedule(LocalDate newDate) {
        this.targetDate = newDate;
    }

    void fundFrom(@Nullable UUID jarId) {
        this.fundingJarId = jarId;
    }

    /** Only ACTIVE ⇄ ACHIEVED; archiving has its own method. */
    void markAs(GoalStatus newStatus) {
        if (newStatus == GoalStatus.ARCHIVED) {
            throw new IllegalArgumentException("archive() archives a goal");
        }
        this.status = newStatus;
    }

    void archive() {
        this.status = GoalStatus.ARCHIVED;
    }

    boolean isArchived() {
        return status == GoalStatus.ARCHIVED;
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY); // @VndAmount already rejected decimals
    }

    UUID id() {
        return id;
    }

    String name() {
        return name;
    }

    BigDecimal targetAmount() {
        return targetAmount;
    }

    LocalDate targetDate() {
        return targetDate;
    }

    @Nullable
    UUID fundingJarId() {
        return fundingJarId;
    }

    GoalStatus status() {
        return status;
    }
}
