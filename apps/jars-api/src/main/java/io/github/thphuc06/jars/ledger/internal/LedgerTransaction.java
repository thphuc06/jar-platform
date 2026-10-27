package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.ledger.TransactionPosted;
import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.shared.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * Row of table {@code transactions} (Flyway V4): what the user did. Named LedgerTransaction to avoid confusion with
 * database transactions and {@code @Transactional}. The amount is never changed after creation (ADR-0014).
 */
@Entity
@Table(name = "transactions")
class LedgerTransaction implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "jar_id", nullable = false)
    private UUID jarId;

    @Column(name = "to_jar_id")
    private UUID toJarId;

    @Column(name = "category_id")
    private UUID categoryId;

    private String counterparty;

    private String note;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, updatable = false)
    private String source;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** See {@link JarEntry}: assigned id and no {@code @Version}, so "new" must be stated explicitly. */
    @Transient
    private boolean persisted;

    protected LedgerTransaction() {} // for JPA

    private LedgerTransaction(
            UUID userId,
            TransactionType type,
            BigDecimal amount,
            Jar jar,
            @Nullable Jar toJar,
            @Nullable UUID categoryId,
            @Nullable String counterparty,
            @Nullable String note,
            Instant occurredAt) {
        this.id = Ids.newId();
        this.userId = userId;
        this.type = type;
        this.amount = amount;
        this.currency = Jar.VND;
        this.jarId = jar.id();
        this.toJarId = toJar == null ? null : toJar.id();
        this.categoryId = categoryId;
        this.counterparty = counterparty;
        this.note = note;
        this.occurredAt = occurredAt;
        this.source = "MANUAL";
    }

    static LedgerTransaction income(
            UUID userId,
            BigDecimal amount,
            Jar jar,
            @Nullable UUID categoryId,
            @Nullable String counterparty,
            @Nullable String note,
            Instant occurredAt) {
        return new LedgerTransaction(
                userId, TransactionType.INCOME, amount, jar, null, categoryId, counterparty, note, occurredAt);
    }

    static LedgerTransaction expense(
            UUID userId,
            BigDecimal amount,
            Jar jar,
            @Nullable UUID categoryId,
            @Nullable String counterparty,
            @Nullable String note,
            Instant occurredAt) {
        return new LedgerTransaction(
                userId, TransactionType.EXPENSE, amount, jar, null, categoryId, counterparty, note, occurredAt);
    }

    static LedgerTransaction transfer(
            UUID userId, BigDecimal amount, Jar from, Jar to, @Nullable String note, Instant occurredAt) {
        return new LedgerTransaction(
                userId, TransactionType.JAR_TRANSFER, amount, from, to, null, null, note, occurredAt);
    }

    TransactionPosted toEvent() {
        return new TransactionPosted(id, userId, type, amount, currency, jarId, toJarId, categoryId, occurredAt);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        persisted = true;
    }

    UUID id() {
        return id;
    }

    TransactionType type() {
        return type;
    }

    BigDecimal amount() {
        return amount;
    }

    String currency() {
        return currency;
    }

    UUID jarId() {
        return jarId;
    }

    @Nullable
    UUID toJarId() {
        return toJarId;
    }

    @Nullable
    UUID categoryId() {
        return categoryId;
    }

    @Nullable
    String counterparty() {
        return counterparty;
    }

    @Nullable
    String note() {
        return note;
    }

    Instant occurredAt() {
        return occurredAt;
    }
}
