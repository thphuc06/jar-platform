package io.github.thphuc06.jars.insights.internal.service;

import io.github.thphuc06.jars.insights.InsightsApi;
import io.github.thphuc06.jars.insights.MonthlyCashflow;
import io.github.thphuc06.jars.insights.OpenAnomaly;
import io.github.thphuc06.jars.insights.SpendingBehaviour;
import io.github.thphuc06.jars.insights.SpendingBehaviour.CategoryBehaviour;
import io.github.thphuc06.jars.insights.internal.detection.SpendingDetector.Direction;
import io.github.thphuc06.jars.insights.internal.model.Anomaly;
import io.github.thphuc06.jars.insights.internal.model.AnomalyStatus;
import io.github.thphuc06.jars.insights.internal.persistence.AnomalyRepository;
import io.github.thphuc06.jars.ledger.CategorySummary;
import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InsightsApiImpl implements InsightsApi {

    private final JdbcClient jdbc;
    private final AnomalyRepository anomalies;
    private final LedgerApi ledger;
    private final Clock clock;

    InsightsApiImpl(JdbcClient jdbc, AnomalyRepository anomalies, LedgerApi ledger, Clock clock) {
        this.jdbc = jdbc;
        this.anomalies = anomalies;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MonthlyCashflow> monthlyCashflow(UserId userId, int lastMonths) {
        if (lastMonths < 1 || lastMonths > MAX_MONTHS) {
            throw new IllegalArgumentException("lastMonths must be between 1 and " + MAX_MONTHS);
        }
        YearMonth current = YearMonth.from(clock.instant().atZone(Months.VIETNAM));
        LocalDate from = Months.startOf(current.minusMonths(lastMonths - 1L));
        return jdbc.sql("""
                        SELECT month, income, expense FROM monthly_cashflow
                        WHERE user_id = ? AND month BETWEEN ? AND ? AND (income <> 0 OR expense <> 0)
                        ORDER BY month
                        """)
                .params(userId.value(), from, Months.startOf(current))
                .query((rs, n) -> new MonthlyCashflow(
                        YearMonth.from(rs.getObject("month", LocalDate.class)),
                        new Money(rs.getBigDecimal("income"), Money.VND),
                        new Money(rs.getBigDecimal("expense"), Money.VND)))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long openAnomalyCount(UserId userId) {
        return anomalies.countByUserIdAndStatus(userId.value(), AnomalyStatus.OPEN);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenAnomaly> openAnomalies(UserId userId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        List<Anomaly> open = anomalies.firstPage(userId.value(), EnumSet.of(AnomalyStatus.OPEN), Limit.of(limit));
        if (open.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> jarNames =
                ledger.jars(userId).stream().collect(Collectors.toMap(JarSummary::id, JarSummary::name));
        // the detector's running mean per jar: the only "usual level" it keeps
        Map<UUID, Money> usual = new HashMap<>();
        jdbc.sql("SELECT jar_id, mean FROM anomaly_detector_state WHERE user_id = ? AND sample_count > 0")
                .params(userId.value())
                .query((rs, n) -> {
                    // the detector works in double; money leaves it as whole VND
                    BigDecimal mean = BigDecimal.valueOf(rs.getDouble("mean")).setScale(0, RoundingMode.HALF_EVEN);
                    if (mean.signum() > 0) {
                        usual.put(rs.getObject("jar_id", UUID.class), new Money(mean, Money.VND));
                    }
                    return null;
                })
                .list();
        return open.stream()
                .map(a -> new OpenAnomaly(
                        jarNames.get(a.getJarId()),
                        kind(a),
                        a.getSeverity().name(),
                        a.amount(),
                        usual.get(a.getJarId()),
                        a.getDetectedAt().atZone(Months.VIETNAM).toLocalDate()))
                .toList();
    }

    private static OpenAnomaly.Kind kind(Anomaly a) {
        return switch (a.getDetector()) {
            case MAD -> OpenAnomaly.Kind.SPIKE;
            case CUSUM -> a.getDirection() == Direction.UP ? OpenAnomaly.Kind.DRIFT_UP : OpenAnomaly.Kind.DRIFT_DOWN;
        };
    }

    @Override
    @Transactional(readOnly = true)
    public SpendingBehaviour spendingBehaviour(UserId userId) {
        LocalDate today = clock.instant().atZone(Months.VIETNAM).toLocalDate();
        YearMonth current = YearMonth.from(today);
        LocalDate currentStart = Months.startOf(current);

        Map<UUID, BigDecimal> thisMonth = new HashMap<>();
        Map<UUID, BigDecimal> pastTotal = new HashMap<>();
        Set<LocalDate> pastMonthsWithSpending = new HashSet<>();
        jdbc.sql("""
                        SELECT category_id, month, spent FROM monthly_category_spend
                        WHERE user_id = ? AND month BETWEEN ? AND ? AND spent > 0
                        """)
                .params(userId.value(), Months.startOf(current.minusMonths(BASELINE_MONTHS)), currentStart)
                .query((rs, n) -> {
                    UUID category = rs.getObject("category_id", UUID.class);
                    LocalDate month = rs.getObject("month", LocalDate.class);
                    BigDecimal spent = rs.getBigDecimal("spent");
                    if (month.equals(currentStart)) {
                        thisMonth.put(category, spent);
                    } else {
                        pastTotal.merge(category, spent, BigDecimal::add);
                        pastMonthsWithSpending.add(month);
                    }
                    return null;
                })
                .list();

        int baselineMonths = pastMonthsWithSpending.size();
        boolean comparable = baselineMonths >= MIN_BASELINE_MONTHS;
        Map<UUID, String> names = ledger.categories(userId).stream()
                .collect(Collectors.toMap(CategorySummary::id, CategorySummary::name));

        List<CategoryBehaviour> categories = thisMonth.entrySet().stream()
                .map(e -> behaviour(
                        DashboardService.categoryName(e.getKey(), names),
                        e.getValue(),
                        comparable ? pastTotal.getOrDefault(e.getKey(), BigDecimal.ZERO) : null,
                        baselineMonths))
                .sorted(Comparator.comparing((CategoryBehaviour c) -> c.spent().amount())
                        .reversed()
                        .thenComparing(CategoryBehaviour::category))
                .limit(MAX_CATEGORIES)
                .toList();
        return new SpendingBehaviour(
                current, today.getDayOfMonth(), current.lengthOfMonth(), baselineMonths, categories);
    }

    /** {@code pastTotal} is null when there is no usual level to compare with. */
    private static CategoryBehaviour behaviour(
            String name, BigDecimal spent, BigDecimal pastTotal, int baselineMonths) {
        Money thisMonth = new Money(spent, Money.VND);
        if (pastTotal == null) {
            return new CategoryBehaviour(name, thisMonth, null, null, null);
        }
        BigDecimal average = pastTotal.divide(BigDecimal.valueOf(baselineMonths), 0, RoundingMode.HALF_EVEN);
        Money usual = new Money(average, Money.VND);
        Money difference = thisMonth.minus(usual);
        Integer percent = usual.isZero()
                ? null
                : difference
                        .amount()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(usual.amount(), 0, RoundingMode.HALF_EVEN)
                        .intValueExact();
        return new CategoryBehaviour(name, thisMonth, usual, difference, percent);
    }
}
