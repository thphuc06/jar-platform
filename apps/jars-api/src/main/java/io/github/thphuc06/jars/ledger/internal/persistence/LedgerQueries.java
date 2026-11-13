package io.github.thphuc06.jars.ledger.internal.persistence;

import io.github.thphuc06.jars.ledger.internal.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries that do not fit an entity: the Money Inbox page and the daily balance series. Plain SQL with joins
 * so a page is one statement however many rows it has (no N+1).
 */
@Repository
public class LedgerQueries {

    /** Optional filters of the Money Inbox; null fields are ignored. {@code to} is exclusive. */
    public record Filter(UUID jarId, UUID categoryId, TransactionType type, Instant from, Instant to) {}

    public record TransactionRow(
            UUID id,
            String type,
            BigDecimal amount,
            String currency,
            UUID jarId,
            String jarName,
            UUID toJarId,
            String toJarName,
            UUID categoryId,
            String categoryName,
            String counterparty,
            String note,
            Instant occurredAt,
            String source) {}

    public record DailyBalance(LocalDate day, BigDecimal balance) {}

    private final JdbcClient jdbc;

    LedgerQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Keyset pagination: rows strictly after the cursor in {@code (occurred_at DESC, id DESC)} order, served by
     * {@code idx_transactions_user_occurred}. Unlike OFFSET the cost does not grow with the page number and rows
     * inserted meanwhile cannot shift a page.
     */
    public List<TransactionRow> page(UUID userId, Filter filter, Instant cursorAt, UUID cursorId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.type, t.amount, t.currency,
                       t.jar_id, j.name AS jar_name, t.to_jar_id, tj.name AS to_jar_name,
                       t.category_id, c.name AS category_name,
                       t.counterparty, t.note, t.occurred_at, t.source
                FROM transactions t
                LEFT JOIN jars j ON j.id = t.jar_id
                LEFT JOIN jars tj ON tj.id = t.to_jar_id
                LEFT JOIN categories c ON c.id = t.category_id
                WHERE t.user_id = :userId
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        if (filter.jarId() != null) {
            sql.append(" AND (t.jar_id = :jarId OR t.to_jar_id = :jarId)");
            params.put("jarId", filter.jarId());
        }
        if (filter.categoryId() != null) {
            sql.append(" AND t.category_id = :categoryId");
            params.put("categoryId", filter.categoryId());
        }
        if (filter.type() != null) {
            sql.append(" AND t.type = :type");
            params.put("type", filter.type().name());
        }
        if (filter.from() != null) {
            sql.append(" AND t.occurred_at >= :from");
            params.put("from", utc(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND t.occurred_at < :to");
            params.put("to", utc(filter.to()));
        }
        if (cursorAt != null) {
            sql.append(" AND (t.occurred_at, t.id) < (:cursorAt, :cursorId)");
            params.put("cursorAt", utc(cursorAt));
            params.put("cursorId", cursorId);
        }
        sql.append(" ORDER BY t.occurred_at DESC, t.id DESC LIMIT :limit");
        params.put("limit", limit);

        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new TransactionRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getObject("jar_id", UUID.class),
                        rs.getString("jar_name"),
                        rs.getObject("to_jar_id", UUID.class),
                        rs.getString("to_jar_name"),
                        rs.getObject("category_id", UUID.class),
                        rs.getString("category_name"),
                        rs.getString("counterparty"),
                        rs.getString("note"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("source")))
                .list();
    }

    /**
     * Total balance of all the user's jars at the end of each day from {@code from} to {@code to} (inclusive), in
     * Vietnam time. The opening balance is everything booked before {@code from}; days without entries repeat the
     * previous balance.
     */
    public List<DailyBalance> dailyBalances(UUID userId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        WITH days AS (
                            SELECT CAST(d AS date) AS day
                            FROM generate_series(CAST(:from AS date), CAST(:to AS date), INTERVAL '1 day') AS d
                        ),
                        base AS (
                            SELECT COALESCE(SUM(e.amount), 0) AS amount
                            FROM jar_entries e JOIN jars j ON j.id = e.jar_id
                            WHERE j.user_id = :userId
                              AND CAST(e.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh' AS date) < CAST(:from AS date)
                        ),
                        daily AS (
                            SELECT CAST(e.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh' AS date) AS day, SUM(e.amount) AS net
                            FROM jar_entries e JOIN jars j ON j.id = e.jar_id
                            WHERE j.user_id = :userId
                              AND CAST(e.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh' AS date)
                                  BETWEEN CAST(:from AS date) AND CAST(:to AS date)
                            GROUP BY 1
                        )
                        SELECT days.day,
                               base.amount + SUM(COALESCE(daily.net, 0)) OVER (ORDER BY days.day) AS balance
                        FROM days CROSS JOIN base LEFT JOIN daily ON daily.day = days.day
                        ORDER BY days.day
                        """)
                .param("userId", userId)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new DailyBalance(rs.getObject("day", LocalDate.class), rs.getBigDecimal("balance")))
                .list();
    }

    /** A jar whose stored balance is not the sum of its entries. {@code difference = stored - computed}. */
    public record BalanceMismatch(UUID jarId, BigDecimal stored, BigDecimal computed, BigDecimal difference) {}

    /**
     * @param jarsChecked every jar in the table, archived ones included
     * @param mismatches how many of them are inconsistent (may be more than {@code worst} holds)
     * @param worst at most the requested number of mismatches, largest absolute difference first
     */
    public record BalanceCheck(long jarsChecked, long mismatches, List<BalanceMismatch> worst) {}

    /**
     * Compares {@code jars.balance} with {@code SUM(jar_entries.amount)} for every jar in one statement (one
     * snapshot, so a posting that commits meanwhile cannot show up as a false mismatch: it changes the entry and the
     * balance in the same transaction). {@code jar_entries.amount} is signed (+ into the jar, - out of it), so a plain
     * SUM is the balance; a jar without entries sums to 0. The CTE is read twice, which makes PostgreSQL scan
     * {@code jar_entries} once. The LEFT JOIN yields one row with null jar columns when nothing is inconsistent.
     */
    public BalanceCheck checkBalances(int limit) {
        return jdbc.sql("""
                        WITH checked AS (
                            SELECT j.id, j.balance AS stored, COALESCE(s.total, 0) AS computed
                            FROM jars j
                            LEFT JOIN (SELECT jar_id, SUM(amount) AS total FROM jar_entries GROUP BY jar_id) s
                                   ON s.jar_id = j.id
                        ),
                        totals AS (
                            SELECT count(*) AS jars_checked,
                                   count(*) FILTER (WHERE stored <> computed) AS mismatches
                            FROM checked
                        )
                        SELECT t.jars_checked, t.mismatches, w.id, w.stored, w.computed, w.stored - w.computed AS difference
                        FROM totals t
                        LEFT JOIN (
                            SELECT id, stored, computed FROM checked WHERE stored <> computed
                            ORDER BY abs(stored - computed) DESC, id
                            LIMIT :limit
                        ) w ON true
                        ORDER BY abs(w.stored - w.computed) DESC NULLS LAST, w.id
                        """).param("limit", limit).query(rs -> {
            long checked = 0;
            long mismatches = 0;
            List<BalanceMismatch> worst = new ArrayList<>();
            while (rs.next()) {
                checked = rs.getLong("jars_checked");
                mismatches = rs.getLong("mismatches");
                UUID id = rs.getObject("id", UUID.class);
                if (id != null) {
                    worst.add(new BalanceMismatch(
                            id,
                            rs.getBigDecimal("stored"),
                            rs.getBigDecimal("computed"),
                            rs.getBigDecimal("difference")));
                }
            }
            return new BalanceCheck(checked, mismatches, List.copyOf(worst));
        });
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
