package io.github.thphuc06.jars.insights.internal.service;

import io.github.thphuc06.jars.insights.InsightsApi;
import io.github.thphuc06.jars.insights.MonthlyCashflow;
import io.github.thphuc06.jars.insights.internal.model.AnomalyStatus;
import io.github.thphuc06.jars.insights.internal.persistence.AnomalyRepository;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InsightsApiImpl implements InsightsApi {

    private final JdbcClient jdbc;
    private final AnomalyRepository anomalies;
    private final Clock clock;

    InsightsApiImpl(JdbcClient jdbc, AnomalyRepository anomalies, Clock clock) {
        this.jdbc = jdbc;
        this.anomalies = anomalies;
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
}
