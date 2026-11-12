package io.github.thphuc06.jars.budgeting.internal.service;

import io.github.thphuc06.jars.budgeting.internal.persistence.JarPeriodStore;
import io.github.thphuc06.jars.budgeting.internal.persistence.JarPeriodStore.Period;
import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Planned against spent per jar for one month (the Budget Jars screen). Every query is scoped to the caller. */
@Service
public class PeriodQueryService {

    /** One active jar for the month. {@code closedAt} is null while the month is open. */
    public record JarPeriodView(
            UUID jarId, String code, String name, Money planned, Money spent, Money remaining, Instant closedAt) {}

    /** The whole month: totals over the listed jars, and {@code closed} once every jar record is closed. */
    public record PeriodView(String month, boolean closed, Money planned, Money spent, List<JarPeriodView> jars) {}

    private final LedgerApi ledger;
    private final JarPeriodStore periods;

    PeriodQueryService(LedgerApi ledger, JarPeriodStore periods) {
        this.ledger = ledger;
        this.periods = periods;
    }

    /**
     * Every active jar appears, with zeros when it has no record for the month, so the screen can show a budget next
     * to what was used. Jars archived since are left out: they have nothing left to plan.
     */
    @Transactional(readOnly = true)
    public PeriodView period(UserId userId, YearMonth month) {
        Map<UUID, Period> byJar = new HashMap<>();
        periods.forMonth(userId, Months.startOf(month)).forEach(p -> byJar.put(p.jarId(), p));

        Money zero = Money.zero(Money.VND);
        Money planned = zero;
        Money spent = zero;
        boolean allClosed = !byJar.isEmpty();
        List<JarSummary> jars = ledger.jars(userId);
        List<JarPeriodView> views = new java.util.ArrayList<>();
        for (JarSummary jar : jars) {
            Period p = byJar.get(jar.id());
            Money jarPlanned = p == null ? zero : p.planned();
            Money jarSpent = p == null ? zero : p.spent();
            Instant closedAt = p == null ? null : p.closedAt();
            allClosed &= closedAt != null;
            planned = planned.plus(jarPlanned);
            spent = spent.plus(jarSpent);
            views.add(new JarPeriodView(
                    jar.id(), jar.code(), jar.name(), jarPlanned, jarSpent, jarPlanned.minus(jarSpent), closedAt));
        }
        return new PeriodView(month.toString(), allClosed && !views.isEmpty(), planned, spent, List.copyOf(views));
    }
}
