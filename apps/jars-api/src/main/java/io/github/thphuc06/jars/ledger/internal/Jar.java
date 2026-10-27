package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.shared.BusinessRuleViolationException;
import io.github.thphuc06.jars.shared.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * Row of table {@code jars} (Flyway V3). {@code balance} is a cache of {@code SUM(jar_entries.amount)} and only changes
 * through {@link #deposit} / {@link #withdraw}, which also produce the matching ledger entry (ADR-0014). Concurrent
 * changes are detected by optimistic locking ({@code version}).
 */
@Entity
@Table(name = "jars")
class Jar {

    static final String VND = "VND"; // BR-5: the MVP only knows VND

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private JarCode code;

    @Column(nullable = false)
    private String name;

    @Column(name = "target_pct", precision = 5, scale = 2)
    private BigDecimal targetPct;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @JdbcTypeCode(SqlTypes.CHAR) // column is CHAR(3); without this, schema validation expects VARCHAR and fails
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    /**
     * Optimistic lock. A wrapper type on purpose: the id is assigned before saving, so Spring Data decides "new
     * entity → persist" from {@code version == null}. With a primitive it would call merge (an extra SELECT).
     */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Jar() {} // for JPA

    private Jar(UUID userId, JarCode code, String name, @Nullable BigDecimal targetPct) {
        if ((code == JarCode.POOL) != (targetPct == null)) {
            throw new IllegalArgumentException("POOL has no target percentage, every other jar has one: " + code);
        }
        this.id = Ids.newId();
        this.userId = userId;
        this.code = code;
        this.name = name;
        this.targetPct = targetPct;
        this.balance = BigDecimal.ZERO;
        this.currency = VND;
    }

    /** A new, empty jar (default jars are inserted with SQL, see {@link JarRepository#insertIfAbsent}). */
    static Jar open(UUID userId, JarCode code, String name, @Nullable BigDecimal targetPct) {
        return new Jar(userId, code, name, targetPct);
    }

    /** Money in. Returns the ledger entry recording it; the caller saves it in the same transaction. */
    JarEntry deposit(BigDecimal amount, EntryType type, UUID transactionId) {
        requireMovement(amount, type, true);
        balance = balance.add(amount);
        return JarEntry.record(this, transactionId, type, amount);
    }

    /** Money out. BR-2: the balance never goes below zero. */
    JarEntry withdraw(BigDecimal amount, EntryType type, UUID transactionId) {
        requireMovement(amount, type, false);
        if (balance.compareTo(amount) < 0) {
            throw new BusinessRuleViolationException(
                    "insufficient-jar-balance",
                    "Jar %s holds %s VND, %s VND requested"
                            .formatted(code, balance.toPlainString(), amount.toPlainString()));
        }
        balance = balance.subtract(amount);
        return JarEntry.record(this, transactionId, type, amount.negate());
    }

    private void requireMovement(BigDecimal amount, EntryType type, boolean moneyIn) {
        if (amount.signum() <= 0 || !type.allows(moneyIn)) {
            throw new IllegalArgumentException("invalid movement " + type + " of " + amount); // a bug, not user input
        }
        if (archivedAt != null) {
            throw new BusinessRuleViolationException("jar-archived", "Jar " + id + " is archived");
        }
    }

    boolean isPool() {
        return code == JarCode.POOL;
    }

    UUID id() {
        return id;
    }

    UUID userId() {
        return userId;
    }

    JarCode code() {
        return code;
    }

    String name() {
        return name;
    }

    @Nullable
    BigDecimal targetPct() {
        return targetPct;
    }

    BigDecimal balance() {
        return balance;
    }

    String currency() {
        return currency;
    }
}
