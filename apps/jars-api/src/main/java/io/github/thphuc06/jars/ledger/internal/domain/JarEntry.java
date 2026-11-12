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
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One line of a jar's ledger. Append-only: every column is non-updatable and no code deletes entries, so the
 * balance of a jar can always be audited as the sum of its entries.
 */
@Entity
@Table(name = "jar_entries")
public class JarEntry {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "jar_id", nullable = false, updatable = false)
    private UUID jarId;

    @Column(name = "transaction_id", updatable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false)
    private EntryType entryType;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, updatable = false)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected JarEntry() {} // for JPA

    JarEntry(UUID jarId, UUID transactionId, EntryType type, Money amount, Money balanceAfter, Instant createdAt) {
        this.jarId = jarId;
        this.transactionId = transactionId;
        this.entryType = type;
        this.amount = amount.amount();
        this.balanceAfter = balanceAfter.amount();
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJarId() {
        return jarId;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }
}
