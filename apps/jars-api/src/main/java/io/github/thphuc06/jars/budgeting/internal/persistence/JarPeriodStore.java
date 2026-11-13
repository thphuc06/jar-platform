package io.github.thphuc06.jars.budgeting.internal.persistence;

import io.github.thphuc06.jars.platform.audit.Audit;
import io.github.thphuc06.jars.platform.audit.AuditAction;
import io.github.thphuc06.jars.platform.audit.Audited;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code jar_periods} table. Every change is an additive upsert (a delta), like the insights read models, so the
 * same set of events gives the same totals in any order, and moving an expense to another jar is a negative delta on
 * one row and a positive one on another.
 */
@Component
public class JarPeriodStore {

    /** One jar's record for one month. */
    public record Period(UUID jarId, LocalDate month, Money planned, Money spent, Instant closedAt) {}

    /** What one jar spent in one month. */
    public record Spending(UUID jarId, LocalDate month, BigDecimal spent) {}

    private final JdbcClient jdbc;

    JarPeriodStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void addSpent(UserId user, UUID jarId, LocalDate month, BigDecimal delta, Instant now) {
        jdbc.sql("""
                        INSERT INTO jar_periods (id, jar_id, user_id, period_month, spent_amount, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (jar_id, period_month) DO UPDATE
                        SET spent_amount = jar_periods.spent_amount + EXCLUDED.spent_amount
                        """)
                .params(Uuids.v7(), jarId, user.value(), month, delta, timestamp(now))
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void addPlanned(UserId user, UUID jarId, LocalDate month, BigDecimal delta, Instant now) {
        jdbc.sql("""
                        INSERT INTO jar_periods (id, jar_id, user_id, period_month, planned_amount, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (jar_id, period_month) DO UPDATE
                        SET planned_amount = jar_periods.planned_amount + EXCLUDED.planned_amount
                        """)
                .params(Uuids.v7(), jarId, user.value(), month, delta, timestamp(now))
                .update();
    }

    /** Makes sure the jar has a (possibly empty) record for the month; an existing one is left alone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensure(UserId user, UUID jarId, LocalDate month, Instant now) {
        jdbc.sql("""
                        INSERT INTO jar_periods (id, jar_id, user_id, period_month, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (jar_id, period_month) DO NOTHING
                        """)
                .params(Uuids.v7(), jarId, user.value(), month, timestamp(now))
                .update();
    }

    /**
     * Closes the user's open records of the month and returns how many it closed; a closed record keeps its time.
     * Audited here, where the month actually closes: the row is written only when something was closed, so a user
     * whose month is already closed (a repeated job) leaves none. The actor is the system (no caller; a scheduled job).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Audited(AuditAction.MONTH_CLOSED)
    public int closeAll(UserId user, LocalDate month, Instant now) {
        int closed = jdbc.sql("""
                        UPDATE jar_periods SET closed_at = ?
                        WHERE user_id = ? AND period_month = ? AND closed_at IS NULL
                        """).params(timestamp(now), user.value(), month).update();
        if (closed == 0) {
            Audit.unchanged();
        } else {
            Audit.entity(user.value()).with("month", YearMonth.from(month)).with("periodsClosed", closed);
        }
        return closed;
    }

    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public List<Period> forMonth(UserId user, LocalDate month) {
        return jdbc.sql("""
                        SELECT jar_id, period_month, planned_amount, spent_amount, closed_at
                        FROM jar_periods WHERE user_id = ? AND period_month = ?
                        ORDER BY jar_id
                        """)
                .params(user.value(), month)
                .query((rs, n) -> {
                    OffsetDateTime closed = rs.getObject("closed_at", OffsetDateTime.class);
                    return new Period(
                            rs.getObject("jar_id", UUID.class),
                            rs.getObject("period_month", LocalDate.class),
                            new Money(rs.getBigDecimal("planned_amount"), Money.VND),
                            new Money(rs.getBigDecimal("spent_amount"), Money.VND),
                            closed == null ? null : closed.toInstant());
                })
                .list();
    }

    /** Spending per jar and month for the months from {@code from} (inclusive) to {@code to} (exclusive). */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public List<Spending> spendingBetween(UserId user, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT jar_id, period_month, spent_amount FROM jar_periods
                        WHERE user_id = ? AND period_month >= ? AND period_month < ? AND spent_amount > 0
                        """)
                .params(user.value(), from, to)
                .query((rs, n) -> new Spending(
                        rs.getObject("jar_id", UUID.class),
                        rs.getObject("period_month", LocalDate.class),
                        rs.getBigDecimal("spent_amount")))
                .list();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
