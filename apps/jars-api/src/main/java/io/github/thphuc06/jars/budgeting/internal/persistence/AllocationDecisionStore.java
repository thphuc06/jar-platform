package io.github.thphuc06.jars.budgeting.internal.persistence;

import io.github.thphuc06.jars.budgeting.internal.domain.DecisionStatus;
import io.github.thphuc06.jars.platform.audit.Audit;
import io.github.thphuc06.jars.platform.audit.AuditAction;
import io.github.thphuc06.jars.platform.audit.AuditEntry;
import io.github.thphuc06.jars.platform.audit.Audited;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The {@code allocation_decisions} table: one row per Idempotency-Key, written once. */
@Component
public class AllocationDecisionStore {

    /** A stored decision, as much of it as a replay needs. */
    public record Decision(UUID id, String requestHash, DecisionStatus status, UUID transactionId) {}

    private final JdbcClient jdbc;

    AllocationDecisionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the decision unless one exists for {@code (user, key)}. Returns true only for the call that wrote it, so
     * that caller (and nobody else) adds the amounts to the jar periods. A repeat, or a concurrent duplicate that lost the
     * race, gets false and no exception.
     *
     * <p>Audited right here, next to the INSERT: the audit row exists exactly when this call wrote the decision (the
     * caller's transaction commits both or neither), and a repeat writes nothing and says so.
     *
     * @param suggested JSON of what was suggested, or null; {@code applied} is JSON of what was applied, or null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Audited(AuditAction.ALLOCATION_DECIDED)
    public boolean insertIfAbsent(
            UUID id,
            UserId user,
            String key,
            String requestHash,
            DecisionStatus status,
            BigDecimal amount,
            LocalDate month,
            UUID transactionId,
            String suggested,
            String applied,
            Instant now) {
        boolean inserted = jdbc.sql("""
                                INSERT INTO allocation_decisions
                                    (id, user_id, idempotency_key, request_hash, decision, amount, period_month,
                                     transaction_id, suggested, applied, created_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                                """)
                        .params(
                                id,
                                user.value(),
                                key,
                                requestHash,
                                status.name(),
                                amount,
                                month,
                                transactionId,
                                suggested,
                                applied,
                                OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
        if (inserted) {
            AuditEntry entry = Audit.entity(id)
                    .with("decision", status)
                    .with("amount", new Money(amount, Money.VND))
                    .with("month", YearMonth.from(month));
            if (transactionId != null) {
                entry.with("transactionId", transactionId);
            }
        } else {
            Audit.unchanged(); // decided before: nothing was written, nothing to audit
        }
        return inserted;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Decision> find(UserId user, String key) {
        return jdbc.sql("""
                        SELECT id, request_hash, decision, transaction_id FROM allocation_decisions
                        WHERE user_id = ? AND idempotency_key = ?
                        """)
                .params(user.value(), key)
                .query((rs, n) -> new Decision(
                        rs.getObject("id", UUID.class),
                        rs.getString("request_hash"),
                        DecisionStatus.valueOf(rs.getString("decision")),
                        rs.getObject("transaction_id", UUID.class)))
                .optional();
    }
}
