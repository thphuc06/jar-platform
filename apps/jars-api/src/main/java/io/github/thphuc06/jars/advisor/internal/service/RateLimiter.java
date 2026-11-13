package io.github.thphuc06.jars.advisor.internal.service;

import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.UserId;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Messages per user per hour and per day, in fixed windows stored in PostgreSQL (ADR-0023). Taking a slot is one
 * statement per window that only increments below the limit, so two concurrent messages cannot both take the last
 * slot; both windows are taken in one transaction, so a message refused by the daily limit does not use an hourly slot.
 */
@Component
class RateLimiter {

    private final JdbcClient jdbc;
    private final AdvisorProperties properties;
    private final Clock clock;

    RateLimiter(JdbcClient jdbc, AdvisorProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /** @throws DomainException 429 with the seconds until the window resets */
    @Transactional
    public void take(UserId userId) {
        Instant now = clock.instant();
        Instant hour = now.truncatedTo(ChronoUnit.HOURS);
        Instant day = now.truncatedTo(ChronoUnit.DAYS);
        // the user's expired windows go with their next message: no scheduled cleanup needed
        jdbc.sql("DELETE FROM advisor_usage WHERE user_id = ? AND window_start < ?")
                .params(userId.value(), Timestamp.from(day.minus(1, ChronoUnit.DAYS)))
                .update();
        if (!increment(userId, "DAY", day, properties.messagesPerDay())) {
            throw limited("ngày", Duration.between(now, day.plus(1, ChronoUnit.DAYS)));
        }
        if (!increment(userId, "HOUR", hour, properties.messagesPerHour())) {
            // undo the daily slot: the whole transaction rolls back with the exception
            throw limited("giờ", Duration.between(now, hour.plus(1, ChronoUnit.HOURS)));
        }
    }

    private boolean increment(UserId userId, String kind, Instant windowStart, int limit) {
        return jdbc.sql("""
                        INSERT INTO advisor_usage (user_id, window_kind, window_start, count) VALUES (?, ?, ?, 1)
                        ON CONFLICT (user_id, window_kind, window_start)
                        DO UPDATE SET count = advisor_usage.count + 1 WHERE advisor_usage.count < ?
                        RETURNING count
                        """)
                .params(userId.value(), kind, Timestamp.from(windowStart), limit)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    private static DomainException limited(String window, Duration retryAfter) {
        return new AdvisorRateLimited(
                "Bạn đã dùng hết lượt hỏi trợ lý trong " + window + " này. Hãy thử lại sau.",
                Math.max(1, retryAfter.toSeconds()));
    }

    /** 429 with Retry-After (seconds). */
    public static class AdvisorRateLimited extends DomainException {

        private final long retryAfterSeconds;

        AdvisorRateLimited(String detail, long retryAfterSeconds) {
            super(AdvisorProblems.RATE_LIMITED, detail);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        @Override
        public java.util.OptionalLong retryAfterSeconds() {
            return java.util.OptionalLong.of(retryAfterSeconds);
        }
    }
}
