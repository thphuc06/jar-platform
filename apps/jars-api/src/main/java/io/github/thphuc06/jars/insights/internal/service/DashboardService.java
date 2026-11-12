package io.github.thphuc06.jars.insights.internal.service;

import io.github.thphuc06.jars.insights.internal.model.AnomalyStatus;
import io.github.thphuc06.jars.insights.internal.persistence.AnomalyRepository;
import io.github.thphuc06.jars.ledger.CategorySummary;
import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the dashboard from the monthly read models (CQRS-lite): one request returns everything the home screen needs,
 * and no query touches the ledger's tables. Jar and category names come from the ledger's public API.
 */
@Service
public class DashboardService {

    public record JarSpend(
            UUID jarId, String code, String name, BigDecimal targetPct, Money balance, Money spent, int txnCount) {}

    public record CategorySpend(UUID categoryId, String name, Money spent, int txnCount) {}

    public record MonthFlow(String month, Money income, Money expense) {}

    public record Dashboard(
            String month,
            Money balance,
            Money income,
            Money expense,
            Money net,
            List<JarSpend> spendByJar,
            List<CategorySpend> spendByCategory,
            List<MonthFlow> trend,
            long openAnomalies) {}

    static final int TREND_MONTHS = 6;

    private final JdbcClient jdbc;
    private final LedgerApi ledger;
    private final AnomalyRepository anomalies;
    private final Clock clock;

    DashboardService(JdbcClient jdbc, LedgerApi ledger, AnomalyRepository anomalies, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.anomalies = anomalies;
        this.clock = clock;
    }

    /** The current month in Vietnam time. */
    public YearMonth currentMonth() {
        return YearMonth.from(clock.instant().atZone(Months.VIETNAM));
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(UserId userId, YearMonth month) {
        UUID user = userId.value();
        LocalDate monthStart = Months.startOf(month);

        List<JarSummary> jars = ledger.jars(userId);
        Money balance = jars.stream().map(JarSummary::balance).reduce(Money.zero(Money.VND), Money::plus);

        Money income = Money.zero(Money.VND);
        Money expense = Money.zero(Money.VND);
        Map<String, MonthFlow> flows = trendFlows(user, month);
        MonthFlow current = flows.get(month.toString());
        if (current != null) {
            income = current.income();
            expense = current.expense();
        }

        return new Dashboard(
                month.toString(),
                balance,
                income,
                expense,
                income.minus(expense),
                spendByJar(user, monthStart, jars),
                spendByCategory(user, monthStart, userId),
                trend(flows, month),
                anomalies.countByUserIdAndStatus(user, AnomalyStatus.OPEN));
    }

    private List<JarSpend> spendByJar(UUID user, LocalDate monthStart, List<JarSummary> jars) {
        record Row(Money spent, int count) {}
        Map<UUID, Row> spent = new HashMap<>();
        jdbc.sql("SELECT jar_id, spent, txn_count FROM monthly_jar_spend WHERE user_id = ? AND month = ?")
                .params(user, monthStart)
                .query((rs, n) -> {
                    spent.put(
                            rs.getObject("jar_id", UUID.class),
                            new Row(new Money(rs.getBigDecimal("spent"), Money.VND), rs.getInt("txn_count")));
                    return null;
                })
                .list();
        // every active jar appears, even with no spending this month, so the screen can show budget against use
        return jars.stream()
                .map(j -> {
                    Row row = spent.getOrDefault(j.id(), new Row(Money.zero(Money.VND), 0));
                    return new JarSpend(
                            j.id(), j.code(), j.name(), j.targetPct(), j.balance(), row.spent(), row.count());
                })
                .toList();
    }

    private List<CategorySpend> spendByCategory(UUID user, LocalDate monthStart, UserId userId) {
        Map<UUID, String> names = ledger.categories(userId).stream()
                .collect(Collectors.toMap(CategorySummary::id, CategorySummary::name));
        List<CategorySpend> result = new ArrayList<>(jdbc.sql("""
                        SELECT category_id, spent, txn_count FROM monthly_category_spend
                        WHERE user_id = ? AND month = ? AND (spent <> 0 OR txn_count <> 0)
                        """)
                .params(user, monthStart)
                .query((rs, n) -> {
                    UUID id = rs.getObject("category_id", UUID.class);
                    boolean none = ReadModelWriter.UNCATEGORIZED.equals(id);
                    return new CategorySpend(
                            none ? null : id,
                            none ? "Chưa phân loại" : names.getOrDefault(id, "Danh mục khác"),
                            new Money(rs.getBigDecimal("spent"), Money.VND),
                            rs.getInt("txn_count"));
                })
                .list());
        result.sort(
                Comparator.comparing((CategorySpend c) -> c.spent().amount()).reversed());
        return result;
    }

    private Map<String, MonthFlow> trendFlows(UUID user, YearMonth month) {
        LocalDate from = Months.startOf(month.minusMonths(TREND_MONTHS - 1L));
        Map<String, MonthFlow> flows = new HashMap<>();
        jdbc.sql("SELECT month, income, expense FROM monthly_cashflow WHERE user_id = ? AND month BETWEEN ? AND ?")
                .params(user, from, Months.startOf(month))
                .query((rs, n) -> {
                    YearMonth m = YearMonth.from(rs.getObject("month", LocalDate.class));
                    flows.put(
                            m.toString(),
                            new MonthFlow(
                                    m.toString(),
                                    new Money(rs.getBigDecimal("income"), Money.VND),
                                    new Money(rs.getBigDecimal("expense"), Money.VND)));
                    return null;
                })
                .list();
        return flows;
    }

    /** The last six months ending at {@code month}, oldest first, with zeros for months without activity. */
    private static List<MonthFlow> trend(Map<String, MonthFlow> flows, YearMonth month) {
        List<MonthFlow> trend = new ArrayList<>();
        for (int i = TREND_MONTHS - 1; i >= 0; i--) {
            String key = month.minusMonths(i).toString();
            trend.add(flows.getOrDefault(key, new MonthFlow(key, Money.zero(Money.VND), Money.zero(Money.VND))));
        }
        return trend;
    }
}
