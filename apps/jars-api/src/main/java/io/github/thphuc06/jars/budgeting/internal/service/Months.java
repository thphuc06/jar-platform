package io.github.thphuc06.jars.budgeting.internal.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/** Budget months are calendar months in Vietnam time, whatever the server's time zone (data-model.md). */
public final class Months {

    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private Months() {}

    /** The month that contains {@code instant}. */
    public static YearMonth of(Instant instant) {
        return YearMonth.from(instant.atZone(VIETNAM));
    }

    public static YearMonth current(Clock clock) {
        return of(clock.instant());
    }

    /** First day of the month, the key stored in {@code jar_periods.period_month}. */
    public static LocalDate startOf(YearMonth month) {
        return month.atDay(1);
    }

    public static LocalDate startOf(Instant instant) {
        return startOf(of(instant));
    }
}
