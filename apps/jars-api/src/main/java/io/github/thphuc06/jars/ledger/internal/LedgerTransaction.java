package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.ledger.TransactionPosted;
import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.shared.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * Row of table {@code transactions} (Flyway V4, V6): what the user did. Named LedgerTransaction to avoid confusion
 * with database transactions and {@code @Transactional}. The amount and type never change (ADR-0014); category, note
 * and jar may be amended (BR-2), always together with the matching ledger entries.
 *
 * <p>Every reference is mapped twice: as an id column that the code writes ({@code jarId}) and as a read-only lazy
 * association ({@code jar}) that list queries can fetch in the same SELECT via the {@link #WITH_REFERENCES} graph.
 */
@Entity
@Table(name = "transactions")
@NamedEntityGraph(
        name = LedgerTransaction.WITH_REFERENCES,
        attributeNodes = {@NamedAttributeNode("jar"), @NamedAttributeNode("toJar"), @NamedAttributeNode("category")})
class LedgerTransaction {

    /** Fetches jar, destination jar and category with the transaction: one query for a whole page, no N+1. */
    static final String WITH_REFERENCES = "LedgerTransaction.withReferences";

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jar_id", insertable = false, updatable = false)
    private Jar jar;

    @Column(name = "to_jar_id", updatable = false)
    private UUID toJarId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_jar_id", insertable = false, updatable = false)
    private Jar toJar;

    @Column(name = "category_id")
    private UUID categoryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", insertable = false, updatable = false)
    private Category category;

    private String counterparty;

    private String note;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, updatable = false)
    private String source;

    /** Optimistic lock (V6); also tells Spring Data that a new transaction must be persisted, not merged. */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

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

    /** Only the reference changes; the caller has already checked that the category fits the type. */
    void changeCategory(@Nullable UUID newCategoryId) {
        this.categoryId = newCategoryId;
    }

    void changeNote(@Nullable String newNote) {
        this.note = newNote;
    }

    /** Only the reference changes; the caller moves the money with a pair of ADJUST entries in the same transaction. */
    void moveTo(Jar newJar) {
        this.jarId = newJar.id();
    }

    UUID id() {
        return id;
    }

    UUID userId() {
        return userId;
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

    /** Lazy: inside a transaction, or loaded with {@link #WITH_REFERENCES}. */
    Jar jar() {
        return jar;
    }

    @Nullable
    UUID toJarId() {
        return toJarId;
    }

    @Nullable
    Jar toJar() {
        return toJar;
    }

    @Nullable
    UUID categoryId() {
        return categoryId;
    }

    @Nullable
    Category category() {
        return category;
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
