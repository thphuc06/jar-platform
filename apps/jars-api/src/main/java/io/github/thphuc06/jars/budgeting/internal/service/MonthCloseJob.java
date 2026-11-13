package io.github.thphuc06.jars.budgeting.internal.service;

import io.github.thphuc06.jars.budgeting.internal.service.MonthCloseService.CloseReport;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService;
import io.github.thphuc06.jars.platform.observability.BusinessMetrics;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a message on the jobs queue means. This is plain application logic with no AWS types, so tests call it
 * directly; the SQS adapter that feeds it exists only under the {@code aws} profile.
 *
 * <p>The EventBridge schedule sends {@code {"job":"MONTH_CLOSE"}} at 00:05 on the 1st (Vietnam time): the job closes the
 * previous month. A message may also name the month ({@code {"job":"MONTH_CLOSE","month":"2026-10"}}) to run a month
 * again by hand. Unknown jobs are ignored (and acknowledged), so a newer scheduler cannot wedge an older task.
 */
@Service
public class MonthCloseJob {

    private static final Logger log = LoggerFactory.getLogger(MonthCloseJob.class);

    public static final String MONTH_CLOSE = "MONTH_CLOSE";

    private final MonthCloseService months;
    private final IdempotencyService idempotency;
    private final JsonMapper json;
    private final Clock clock;
    private final ObservationRegistry observations;

    MonthCloseJob(
            MonthCloseService months,
            IdempotencyService idempotency,
            JsonMapper json,
            Clock clock,
            ObservationRegistry observations) {
        this.observations = observations;
        this.months = months;
        this.idempotency = idempotency;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @return the report of the month close, or null when the message names another job
     * @throws IllegalArgumentException for a payload that is not JSON or names a month that is not {@code YYYY-MM}
     * @throws IllegalStateException when some users could not be closed, so the message is delivered again
     */
    public CloseReport handle(String payload) {
        JsonNode message;
        try {
            message = json.readTree(payload);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The job message is not valid JSON", e);
        }
        String job = message.hasNonNull("job") ? message.get("job").asString() : "";
        if (!MONTH_CLOSE.equals(job)) {
            log.warn("Ignoring a message for an unknown job: '{}'", job);
            return null;
        }
        YearMonth month =
                message.hasNonNull("month") ? parseMonth(message.get("month").asString()) : previousMonth();

        // one observation = a timer metric (BusinessMetrics.MONTH_CLOSE) and a span in the job's trace
        Observation observation = Observation.createNotStarted(BusinessMetrics.MONTH_CLOSE, observations)
                .lowCardinalityKeyValue("trigger", message.hasNonNull("month") ? "manual" : "schedule")
                .highCardinalityKeyValue("month", month.toString());
        CloseReport report = observation.observe(() -> months.closeAll(month));
        if (report.failed() > 0) {
            throw new IllegalStateException(
                    "Month " + month + " was not closed for " + report.failed() + " users; it will be retried");
        }
        purgeExpiredKeys();
        return report;
    }

    /** The month before the current one in Vietnam time: at 00:05 on the 1st (UTC+7) that is still the 31st in UTC. */
    private YearMonth previousMonth() {
        return Months.current(clock).minusMonths(1);
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("The month of the job message must look like 2026-10: " + month, e);
        }
    }

    /** The monthly job also drops expired Idempotency-Keys (IdempotencyService documents this), so no scheduler is needed. */
    private void purgeExpiredKeys() {
        try {
            log.info("Purged {} expired idempotency keys", idempotency.purgeExpired());
        } catch (RuntimeException e) {
            log.warn("Purging expired idempotency keys failed; the next run will try again", e);
        }
    }
}
