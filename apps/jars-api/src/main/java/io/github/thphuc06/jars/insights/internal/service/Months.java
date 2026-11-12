package io.github.thphuc06.jars.insights.internal.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/** Budget months are calendar months in Vietnam time, whatever the server's time zone (data-model.md). */
final class Months {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private Months() {}

    /** First day of the Vietnamese month that contains {@code instant}; the key used by the monthly read models. */
    static LocalDate startOf(Instant instant) {
        return instant.atZone(VIETNAM).toLocalDate().withDayOfMonth(1);
    }

    static LocalDate startOf(YearMonth month) {
        return month.atDay(1);
    }
}
