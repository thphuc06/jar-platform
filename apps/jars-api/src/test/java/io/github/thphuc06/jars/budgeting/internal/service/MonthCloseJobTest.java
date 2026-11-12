package io.github.thphuc06.jars.budgeting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.thphuc06.jars.budgeting.internal.service.MonthCloseService.CloseReport;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** What a message on the jobs queue means: the month it closes, and what it does with bad messages. */
class MonthCloseJobTest {

    private final MonthCloseService months = mock(MonthCloseService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final JsonMapper json = JsonMapper.builder().build();

    private MonthCloseJob jobAt(String instant) {
        return new MonthCloseJob(months, idempotency, json, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        when(months.closeAll(any())).thenAnswer(call -> new CloseReport(call.getArgument(0), false, 3, 3, 0));
    }

    @ParameterizedTest(name = "{0} (UTC) closes {1}")
    @CsvSource({
        // the schedule fires at 00:05 on the 1st in Vietnam time (UTC+7), which is 17:05 on the last day in UTC
        "2026-10-31T17:05:00Z, 2026-10",
        "2026-11-30T17:05:00Z, 2026-11",
        "2026-12-31T17:05:00Z, 2026-12",
        "2025-12-31T17:05:00Z, 2025-12",
        // a run later in the day, or on another day of the month, still closes the month before the current one
        "2026-11-01T03:00:00Z, 2026-10",
        "2026-11-15T12:00:00Z, 2026-10",
        "2026-01-01T00:05:00Z, 2025-12",
        "2026-03-31T16:59:00Z, 2026-02"
    })
    void withoutAMonth_theJobClosesTheMonthBeforeTheCurrentOneInVietnamTime(String now, String expected) {
        CloseReport report = jobAt(now).handle("{\"job\":\"MONTH_CLOSE\"}");

        assertThat(report.month()).isEqualTo(YearMonth.parse(expected));
        verify(months).closeAll(YearMonth.parse(expected));
    }

    @Test
    void aMessageThatNamesTheMonth_closesThatMonth() {
        CloseReport report = jobAt("2026-11-12T03:00:00Z").handle("{\"job\":\"MONTH_CLOSE\",\"month\":\"2026-08\"}");

        assertThat(report.month()).isEqualTo(YearMonth.of(2026, 8));
        verify(months).closeAll(YearMonth.of(2026, 8));
    }

    @Test
    void aSuccessfulRun_alsoPurgesExpiredIdempotencyKeys() {
        when(idempotency.purgeExpired()).thenReturn(12);

        jobAt("2026-11-12T03:00:00Z").handle("{\"job\":\"MONTH_CLOSE\"}");

        verify(idempotency).purgeExpired();
    }

    @Test
    void aFailingPurge_doesNotFailTheMonthClose() {
        when(idempotency.purgeExpired()).thenThrow(new IllegalStateException("database is busy"));

        CloseReport report = jobAt("2026-11-12T03:00:00Z").handle("{\"job\":\"MONTH_CLOSE\"}");

        assertThat(report.failed()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"job\":\"PURGE_EVERYTHING\"}", "{}", "{\"job\":null}", "{\"month\":\"2026-10\"}", "[]"})
    void anUnknownJob_isIgnoredAndNothingRuns(String payload) {
        CloseReport report = jobAt("2026-11-12T03:00:00Z").handle(payload);

        assertThat(report).isNull();
        verifyNoInteractions(months, idempotency);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"job\":", "MONTH_CLOSE"})
    void aPayloadThatIsNotJson_isRejected_soTheQueueRedeliversItAndFinallyDeadLettersIt(String payload) {
        assertThatThrownBy(() -> jobAt("2026-11-12T03:00:00Z").handle(payload))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(months);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13", "October", "2026-1", "202610"})
    void aMonthThatIsNotYearMonth_isRejected(String month) {
        assertThatThrownBy(() ->
                        jobAt("2026-11-12T03:00:00Z").handle("{\"job\":\"MONTH_CLOSE\",\"month\":\"" + month + "\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(month);
        verifyNoInteractions(months);
    }

    @Test
    void whenSomeUsersCouldNotBeClosed_theMessageFails_soItIsDeliveredAgain() {
        when(months.closeAll(any())).thenReturn(new CloseReport(YearMonth.of(2026, 10), false, 5, 3, 2));

        assertThatThrownBy(() -> jobAt("2026-11-12T03:00:00Z").handle("{\"job\":\"MONTH_CLOSE\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 users");
        verify(idempotency, never()).purgeExpired();
    }
}
