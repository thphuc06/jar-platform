package io.github.thphuc06.jars.shared;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Instants are stored in UTC, but business dates ("today", a budget month, a goal deadline) are Vietnamese dates. The
 * server runs in UTC, so between 17:00 and 24:00 UTC it is already "tomorrow" in Vietnam.
 */
public final class BusinessTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private BusinessTime() {}

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
