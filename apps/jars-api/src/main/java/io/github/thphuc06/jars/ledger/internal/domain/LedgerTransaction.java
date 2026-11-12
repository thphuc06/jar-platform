package io.github.thphuc06.jars.ledger.internal.domain;

import io.github.thphuc06.jars.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Something the user did with money. The amount is always positive and never edited afterwards (a mistake is
 * corrected with an adjusting entry); only the category and the jar of an expense can be changed.
 */
@Entity
@Table(name = "transactions")
public class LedgerTransaction {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private TransactionType type;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "jar_id")
    private UUID jarId;

    @Column(name = "to_jar_id", updatable = false)
    private UUID toJarId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "counterparty", updatable = false)
    private String counterparty;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false)
    private TransactionSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {} // for JPA

    private LedgerTransaction(
            UUID userId,
            TransactionType type,
            Money amount,
            UUID jarId,
            UUID toJarId,
            UUID categoryId,
            String counterparty,
            String note,
            Instant occurredAt,
            TransactionSource source,
            Instant now) {
        this.userId = userId;
        this.type = type;
        this.amount = amount.amount();
        this.currency = amount.currency().getCurrencyCode();
        this.jarId = jarId;
        this.toJarId = toJarId;
        this.categoryId = categoryId;
        this.counterparty = counterparty;
        this.note = note;
        this.occurredAt = occurredAt;
        this.source = source;
        this.createdAt = now;
    }

    public static LedgerTransaction expense(
            UUID userId,
            Money amount,
            UUID jarId,
            UUID categoryId,
            String counterparty,
            String note,
            Instant occurredAt,
            Instant now) {
        return expense(
                userId, amount, jarId, categoryId, counterparty, note, occurredAt, TransactionSource.MANUAL, now);
    }

    public static LedgerTransaction expense(
            UUID userId,
            Money amount,
            UUID jarId,
            UUID categoryId,
            String counterparty,
            String note,
            Instant occurredAt,
            TransactionSource source,
            Instant now) {
        return new LedgerTransaction(
                userId,
                TransactionType.EXPENSE,
                amount,
                jarId,
                null,
                categoryId,
                counterparty,
                note,
                occurredAt,
                source,
                now);
    }

    /** Income, optionally put straight into one jar ({@code jarId} may be null). */
    public static LedgerTransaction income(
            UUID userId,
            Money amount,
            UUID jarId,
            UUID categoryId,
            String counterparty,
            String note,
            Instant occurredAt,
            Instant now) {
        return income(userId, amount, jarId, categoryId, counterparty, note, occurredAt, TransactionSource.MANUAL, now);
    }

    public static LedgerTransaction income(
            UUID userId,
            Money amount,
            UUID jarId,
            UUID categoryId,
            String counterparty,
            String note,
            Instant occurredAt,
            TransactionSource source,
            Instant now) {
        return new LedgerTransaction(
                userId,
                TransactionType.INCOME,
                amount,
                jarId,
                null,
                categoryId,
                counterparty,
                note,
                occurredAt,
                source,
                now);
    }

    public static LedgerTransaction transfer(
            UUID userId, Money amount, UUID fromJarId, UUID toJarId, String note, Instant now) {
        return new LedgerTransaction(
                userId,
                TransactionType.JAR_TRANSFER,
                amount,
                fromJarId,
                toJarId,
                null,
                null,
                note,
                now,
                TransactionSource.MANUAL,
                now);
    }

    /** Applies the new category and/or jar; a null argument keeps the current value. Returns true if anything changed. */
    public boolean recategorize(UUID newCategoryId, UUID newJarId) {
        boolean changed = false;
        if (newCategoryId != null && !newCategoryId.equals(categoryId)) {
            this.categoryId = newCategoryId;
            changed = true;
        }
        if (newJarId != null && !newJarId.equals(jarId)) {
            this.jarId = newJarId;
            changed = true;
        }
        return changed;
    }

    public Money money() {
        return new Money(amount, Currency.getInstance(currency));
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public TransactionType getType() {
        return type;
    }

    public UUID getJarId() {
        return jarId;
    }

    public UUID getToJarId() {
        return toJarId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public String getNote() {
        return note;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public TransactionSource getSource() {
        return source;
    }
}
