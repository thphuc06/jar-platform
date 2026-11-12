package io.github.thphuc06.jars.ledger.internal.domain;

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
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A budget jar. {@code balance} is a cache of the sum of the jar's entries and {@code version} is the optimistic
 * lock: two requests that both read version N cannot both write N+1, so a balance is never silently overwritten
 * (ADR-0014). All changes to {@code balance} go through {@link #post}, which also produces the ledger entry.
 */
@Entity
@Table(name = "jars")
public class Jar {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "code", nullable = false, updatable = false)
    private JarCode code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "target_pct", nullable = false)
    private BigDecimal targetPct;

    @Column(name = "balance", nullable = false)
    private BigDecimal balance;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Jar() {} // for JPA

    public Jar(UUID userId, JarCode code, String name, BigDecimal targetPct, Currency currency, Instant now) {
        this.userId = userId;
        this.code = code;
        this.name = name;
        this.targetPct = targetPct.setScale(2);
        this.balance = BigDecimal.ZERO.setScale(2);
        this.currency = currency.getCurrencyCode();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Moves the balance by {@code signedAmount} and returns the matching ledger entry. The caller persists the entry
     * in the same transaction; if another request changed this jar meanwhile, saving it fails on the version check.
     */
    public JarEntry post(EntryType type, Money signedAmount, UUID transactionId, Instant occurredAt, Instant now) {
        requireActive();
        if (signedAmount.isZero()) {
            throw new IllegalArgumentException("A ledger entry must not be zero");
        }
        Money newBalance = balance().plus(signedAmount);
        this.balance = newBalance.amount();
        this.updatedAt = now;
        return new JarEntry(id, transactionId, type, signedAmount, newBalance, occurredAt);
    }

    /** Fails with 422 unless the jar holds at least {@code amount}. */
    public void requireAtLeast(Money amount) {
        if (balance().compareTo(amount) < 0) {
            throw new DomainException(
                    LedgerProblems.INSUFFICIENT_JAR_BALANCE,
                    "Hũ " + name + " còn " + balance() + ", không đủ để chi " + amount + ".");
        }
    }

    public void rename(String newName, Instant now) {
        this.name = newName;
        this.updatedAt = now;
    }

    public void changeTargetPct(BigDecimal newPct, Instant now) {
        this.targetPct = newPct.setScale(2);
        this.updatedAt = now;
    }

    /** Archiving needs an empty jar, and releases its share of the budget. */
    public void archive(Instant now) {
        if (balance.signum() != 0) {
            throw new DomainException(
                    LedgerProblems.JAR_NOT_EMPTY,
                    "Hũ " + name + " còn " + balance() + ". Hãy chuyển hết tiền sang hũ khác trước.");
        }
        this.archivedAt = now;
        this.targetPct = BigDecimal.ZERO.setScale(2);
        this.updatedAt = now;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public void requireActive() {
        if (isArchived()) {
            throw new DomainException(LedgerProblems.JAR_ARCHIVED, "Hũ " + name + " đã được lưu trữ.");
        }
    }

    public Money balance() {
        return new Money(balance, Currency.getInstance(currency));
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public JarCode getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getTargetPct() {
        return targetPct;
    }

    public Currency getCurrency() {
        return Currency.getInstance(currency);
    }

    public long getVersion() {
        return version;
    }
}
