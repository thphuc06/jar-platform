package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.shared.BusinessTime;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /dashboard and GET /balances/daily: aggregate SQL over the ledger tables, answered straight from
 * PostgreSQL ({@code GROUP BY}, {@code FILTER}, {@code generate_series}, a window function) instead of loading rows
 * into Java. Lives in the ledger for now because only the ledger may read its tables; Phase 2 (P2.3) moves the
 * dashboard to the insights module on top of a read model fed by events.
 *
 * <p>Months and days are Vietnamese: a month is {@code [1st 00:00, next 1st 00:00)} in {@code Asia/Ho_Chi_Minh},
 * converted to instants in Java so the queries compare {@code occurred_at} directly and can use the index.
 */
@Service
public class DashboardQueries {

    static final int TREND_MONTHS = 6;
    private static final String ZONE = BusinessTime.ZONE.getId();

    private final JdbcClient jdbc;

    DashboardQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Dashboard(
            YearMonth month,
            BigDecimal total,
            BigDecimal unallocated,
            BigDecimal income,
            BigDecimal expense,
            List<CategorySpend> byCategory,
            List<JarSpend> byJar,
            List<MonthTotals> trend) {}

    /** {@code categoryId == null}: expenses recorded without a category. */
    public record CategorySpend(
            @Nullable UUID categoryId, @Nullable String name, BigDecimal amount) {}

    public record JarSpend(UUID jarId, JarCode code, String name, BigDecimal balance, BigDecimal spent) {}

    public record MonthTotals(YearMonth month, BigDecimal income, BigDecimal expense) {}

    public record DailyBalance(LocalDate date, BigDecimal balance) {}

    /**
     * Several aggregate queries, one consistent picture: REPEATABLE READ gives the whole transaction ONE snapshot. With
     * the default READ COMMITTED each statement sees the latest commits, so an expense committed between two of them
     * could show up in "by jar" but not in "by category".
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Dashboard dashboard(UUID userId, YearMonth month) {
        List<JarSpend> jars = jarsWithSpending(userId, month);
        BigDecimal total = jars.stream().map(JarSpend::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unallocated = jars.stream()
                .filter(jar -> jar.code() == JarCode.POOL)
                .map(JarSpend::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<MonthTotals> trend = trend(userId, month);
        MonthTotals current = trend.getLast(); // the trend ends with the requested month: no extra query for totals
        return new Dashboard(
                month,
                total,
                unallocated,
                current.income(),
                current.expense(),
                spendingByCategory(userId, month),
                jars.stream().filter(jar -> jar.code() != JarCode.POOL).toList(),
                trend);
    }

    /**
     * Total balance at the END of each of the last {@code days} Vietnamese days, today included. Built from the
     * transactions by {@code occurred_at} (a back-dated expense lowers the curve on its own day): an opening balance
     * before the window, plus a running sum (window function) of the daily changes. Transfers between jars do not
     * change the total, so they are left out.
     */
    @Transactional(readOnly = true)
    public List<DailyBalance> dailyBalances(UUID userId, LocalDate today, int days) {
        LocalDate first = today.minusDays(days - 1L);
        return jdbc.sql("""
                        WITH signed AS (
                            SELECT occurred_at,
                                   CASE type WHEN 'INCOME' THEN amount ELSE -amount END AS delta
                            FROM transactions
                            WHERE user_id = :userId AND type <> 'JAR_TRANSFER'
                        ),
                        opening AS (
                            SELECT COALESCE(SUM(delta), 0) AS amount FROM signed WHERE occurred_at < :from
                        ),
                        daily AS (
                            SELECT (occurred_at AT TIME ZONE :zone)::date AS day, SUM(delta) AS delta
                            FROM signed
                            WHERE occurred_at >= :from AND occurred_at < :to
                            GROUP BY 1
                        )
                        SELECT d::date AS day,
                               (SELECT amount FROM opening)
                                   + SUM(COALESCE(daily.delta, 0)) OVER (ORDER BY d) AS balance
                        FROM generate_series(CAST(:first AS date), CAST(:last AS date), interval '1 day') AS d
                        LEFT JOIN daily ON daily.day = d::date
                        ORDER BY d
                        """)
                .param("userId", userId)
                .param("zone", ZONE)
                .param("from", startOf(first))
                .param("to", startOf(today.plusDays(1)))
                .param("first", first)
                .param("last", today)
                .query((rs, n) -> new DailyBalance(rs.getObject("day", LocalDate.class), rs.getBigDecimal("balance")))
                .list();
    }

    /** Every jar (POOL included, archived excluded) with what was spent from it in the month; 0 when nothing. */
    private List<JarSpend> jarsWithSpending(UUID userId, YearMonth month) {
        return jdbc
                .sql("""
                        SELECT j.id, j.code, j.name, j.balance, COALESCE(SUM(t.amount), 0) AS spent
                        FROM jars j
                        LEFT JOIN transactions t
                               ON t.jar_id = j.id AND t.type = 'EXPENSE'
                              AND t.occurred_at >= :from AND t.occurred_at < :to
                        WHERE j.user_id = :userId AND j.archived_at IS NULL
                        GROUP BY j.id
                        """)
                .param("userId", userId)
                .param("from", startOf(month.atDay(1)))
                .param("to", startOf(month.plusMonths(1).atDay(1)))
                .query((rs, n) -> new JarSpend(
                        rs.getObject("id", UUID.class),
                        JarCode.valueOf(rs.getString("code")),
                        rs.getString("name"),
                        rs.getBigDecimal("balance"),
                        rs.getBigDecimal("spent")))
                .list()
                .stream()
                .sorted(Comparator.comparing(JarSpend::code).thenComparing(JarSpend::name)) // BR-1 order
                .toList();
    }

    /** Biggest first. LEFT JOIN: expenses without a category are grouped under a null category. */
    private List<CategorySpend> spendingByCategory(UUID userId, YearMonth month) {
        return jdbc.sql("""
                        SELECT c.id, c.name, SUM(t.amount) AS amount
                        FROM transactions t
                        LEFT JOIN categories c ON c.id = t.category_id
                        WHERE t.user_id = :userId AND t.type = 'EXPENSE'
                          AND t.occurred_at >= :from AND t.occurred_at < :to
                        GROUP BY c.id, c.name
                        ORDER BY amount DESC, c.name
                        """)
                .param("userId", userId)
                .param("from", startOf(month.atDay(1)))
                .param("to", startOf(month.plusMonths(1).atDay(1)))
                .query((rs, n) -> new CategorySpend(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getBigDecimal("amount")))
                .list();
    }

    /**
     * Income and expense of the {@value #TREND_MONTHS} months ending with {@code month}, oldest first. {@code
     * generate_series} produces every month, so a month without any transaction is a row of zeros, not a gap. The
     * range condition on {@code occurred_at} keeps the index usable; the Vietnamese month is computed afterwards.
     */
    private List<MonthTotals> trend(UUID userId, YearMonth month) {
        YearMonth first = month.minusMonths(TREND_MONTHS - 1L);
        return jdbc.sql("""
                        SELECT to_char(m, 'YYYY-MM') AS month,
                               COALESCE(SUM(t.amount) FILTER (WHERE t.type = 'INCOME'), 0) AS income,
                               COALESCE(SUM(t.amount) FILTER (WHERE t.type = 'EXPENSE'), 0) AS expense
                        FROM generate_series(CAST(:firstMonth AS timestamp), CAST(:lastMonth AS timestamp),
                                             interval '1 month') AS m
                        LEFT JOIN transactions t
                               ON t.user_id = :userId
                              AND t.occurred_at >= :from AND t.occurred_at < :to
                              AND date_trunc('month', t.occurred_at AT TIME ZONE :zone) = m
                        GROUP BY m
                        ORDER BY m
                        """)
                .param("userId", userId)
                .param("zone", ZONE)
                .param("firstMonth", first.atDay(1))
                .param("lastMonth", month.atDay(1))
                .param("from", startOf(first.atDay(1)))
                .param("to", startOf(month.plusMonths(1).atDay(1)))
                .query((rs, n) -> new MonthTotals(
                        YearMonth.parse(rs.getString("month")),
                        rs.getBigDecimal("income"),
                        rs.getBigDecimal("expense")))
                .list();
    }

    /** 00:00 of that Vietnamese day, as a UTC instant (OffsetDateTime: the PostgreSQL driver cannot bind Instant). */
    private static OffsetDateTime startOf(LocalDate day) {
        return day.atStartOfDay(BusinessTime.ZONE).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }
}
