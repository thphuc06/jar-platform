package io.github.thphuc06.jars.ledger.internal;

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
 * Row of table {@code jars} (Flyway V3). {@code balance} is a cache of the jar's ledger entries, protected by
 * optimistic locking ({@code version}); entries and the code that moves money arrive in P1.5 (ADR-0014).
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

    /** A new, empty jar. Money only enters it through ledger entries (P1.5). */
    static Jar open(UUID userId, JarCode code, String name, @Nullable BigDecimal targetPct) {
        return new Jar(userId, code, name, targetPct);
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
