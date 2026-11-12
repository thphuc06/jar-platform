package io.github.thphuc06.jars.budgeting;

import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * A user's budget month was closed (flow F6). Published inside the transaction that closes it, so listeners that use
 * {@code @ApplicationModuleListener} only see it after the commit. A user's month is closed at most once, so the event
 * is published at most once per user and month; consumers still ignore a redelivered {@code eventId}.
 *
 * @param eventId unique per publication (UUIDv7)
 * @param month the month that was closed
 * @param summary planned and spent per jar that has a record for the month, active or archived
 */
public record MonthClosed(UUID eventId, UserId userId, YearMonth month, List<JarPeriodSummary> summary) {

    /** What one jar planned and spent in the month. */
    public record JarPeriodSummary(UUID jarId, String jarName, Money planned, Money spent) {}

    public Money totalPlanned() {
        return summary.stream().map(JarPeriodSummary::planned).reduce(Money.zero(Money.VND), Money::plus);
    }

    public Money totalSpent() {
        return summary.stream().map(JarPeriodSummary::spent).reduce(Money.zero(Money.VND), Money::plus);
    }
}
