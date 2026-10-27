package io.github.thphuc06.jars.ledger.internal;

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
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/**
 * One line of a jar's append-only ledger (Flyway V4). Created only by {@link Jar#deposit} / {@link Jar#withdraw}.
 * {@code @Immutable}: Hibernate never issues an UPDATE for it; the database trigger forbids UPDATE and DELETE anyway.
 */
@Entity
@Immutable
@Table(name = "jar_entries")
class JarEntry implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "jar_id", nullable = false)
    private UUID jarId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private EntryType entryType;

    /** Signed: positive into the jar, negative out of it. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * The id is assigned by the application and there is no {@code @Version}, so Spring Data cannot tell a new entry
     * from an existing one and would call merge (SELECT, then INSERT). {@link Persistable#isNew} answers explicitly.
     */
    @Transient
    private boolean persisted;

    protected JarEntry() {} // for JPA

    private JarEntry(Jar jar, UUID transactionId, EntryType entryType, BigDecimal amount) {
        this.id = Ids.newId();
        this.jarId = jar.id();
        this.transactionId = transactionId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = jar.balance();
    }

    /** Called right after the jar's balance changed, so {@code balanceAfter} is the new balance. */
    static JarEntry record(Jar jar, UUID transactionId, EntryType entryType, BigDecimal signedAmount) {
        return new JarEntry(jar, transactionId, entryType, signedAmount);
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

    UUID jarId() {
        return jarId;
    }

    EntryType entryType() {
        return entryType;
    }

    BigDecimal amount() {
        return amount;
    }

    BigDecimal balanceAfter() {
        return balanceAfter;
    }
}
