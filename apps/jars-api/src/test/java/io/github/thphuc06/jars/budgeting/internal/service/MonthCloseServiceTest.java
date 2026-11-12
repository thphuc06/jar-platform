package io.github.thphuc06.jars.budgeting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.thphuc06.jars.budgeting.internal.persistence.JarPeriodStore;
import io.github.thphuc06.jars.budgeting.internal.service.MonthCloseService.CloseReport;
import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.platform.idempotency.ProcessedMessages;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The batch logic of the month close with every collaborator replaced: paging through the users 500 at a time, one
 * failing user not stopping the others, and the batch marker. What the close does to the data is covered against a
 * real database in {@code MonthCloseTest}.
 */
class MonthCloseServiceTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    private final IdentityApi identity = mock(IdentityApi.class);
    private final LedgerApi ledger = mock(LedgerApi.class);
    private final JarPeriodStore periods = mock(JarPeriodStore.class);
    private final ProcessedMessages inbox = mock(ProcessedMessages.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TransactionTemplate tx = new TransactionTemplate(new NoTransactions());
    // 2026-11-12 in Vietnam: October is over, November is running
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneOffset.UTC);

    private MonthCloseService service;

    /** A transaction manager that does nothing: these tests care about the calls, not about the database. */
    static final class NoTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {}

        @Override
        public void rollback(TransactionStatus status) {}
    }

    @BeforeEach
    void setUp() {
        when(ledger.jars(any())).thenReturn(List.of());
        when(inbox.firstTime(anyString(), anyString())).thenReturn(true);
        service = new MonthCloseService(identity, ledger, periods, inbox, events, tx, clock);
    }

    /** {@code count} users with ascending ids, served by keyset paging like the real IdentityApi. */
    private List<UserId> users(int count) {
        List<UserId> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            users.add(new UserId(new UUID(0x0190_0000_0000_7000L, i + 1L)));
        }
        when(identity.userIdsAfter(any(), anyInt())).thenAnswer(call -> {
            UUID cursor = call.getArgument(0);
            int limit = call.getArgument(1);
            return users.stream()
                    .filter(u -> cursor == null || u.value().compareTo(cursor) > 0)
                    .limit(limit)
                    .toList();
        });
        return users;
    }

    @Test
    void closeAll_readsTheUsersInPagesOfFiveHundred_andClosesEachOne() {
        List<UserId> users = users(1_203);

        CloseReport report = service.closeAll(OCTOBER);

        assertThat(report).isEqualTo(new CloseReport(OCTOBER, false, 1_203, 1_203, 0));
        ArgumentCaptor<UUID> cursors = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(identity, times(4)).userIdsAfter(cursors.capture(), limits.capture());
        assertThat(limits.getAllValues()).containsOnly(500);
        assertThat(cursors.getAllValues())
                .as("first page from the start, then after the last id of the previous page, and one empty page")
                .containsExactly(
                        null,
                        users.get(499).value(),
                        users.get(999).value(),
                        users.get(1_202).value());
        verify(inbox, times(1_203)).firstTime(eq(MonthCloseService.USER_CONSUMER), contains(":2026-10"));
        verify(inbox).firstTime(MonthCloseService.BATCH_CONSUMER, "2026-10");
    }

    @Test
    void closeAll_withNoUsers_stillMarksTheBatchDone() {
        users(0);

        CloseReport report = service.closeAll(OCTOBER);

        assertThat(report).isEqualTo(new CloseReport(OCTOBER, false, 0, 0, 0));
        verify(inbox).firstTime(MonthCloseService.BATCH_CONSUMER, "2026-10");
    }

    @Test
    void closeAll_whenTheBatchWasCompletedBefore_visitsNobody() {
        when(inbox.contains(MonthCloseService.BATCH_CONSUMER, "2026-10")).thenReturn(true);

        CloseReport report = service.closeAll(OCTOBER);

        assertThat(report).isEqualTo(new CloseReport(OCTOBER, true, 0, 0, 0));
        verifyNoInteractions(identity);
        verify(inbox, never()).firstTime(anyString(), anyString());
    }

    @Test
    void closeAll_aUserThatFails_doesNotStopTheOthers_andLeavesTheBatchOpenForARetry() {
        List<UserId> users = users(7);
        UserId broken = users.get(3);
        when(inbox.firstTime(eq(MonthCloseService.USER_CONSUMER), contains(broken.toString())))
                .thenThrow(new IllegalStateException("database is busy"));

        CloseReport report = service.closeAll(OCTOBER);

        assertThat(report).isEqualTo(new CloseReport(OCTOBER, false, 7, 6, 1));
        verify(inbox, never()).firstTime(eq(MonthCloseService.BATCH_CONSUMER), anyString());
    }

    @Test
    void closeAll_usersThatWereClosedBefore_arePassedOverAndNotCountedAsClosed() {
        List<UserId> users = users(4);
        when(inbox.firstTime(
                        eq(MonthCloseService.USER_CONSUMER),
                        contains(users.get(0).toString())))
                .thenReturn(false);
        when(inbox.firstTime(
                        eq(MonthCloseService.USER_CONSUMER),
                        contains(users.get(2).toString())))
                .thenReturn(false);

        CloseReport report = service.closeAll(OCTOBER);

        assertThat(report).isEqualTo(new CloseReport(OCTOBER, false, 4, 2, 0));
        verify(inbox).firstTime(MonthCloseService.BATCH_CONSUMER, "2026-10");
    }

    @Test
    void closeMonth_reportsWhetherItClosedAnythingNow() {
        UserId user = new UserId(UUID.randomUUID());
        when(inbox.firstTime(MonthCloseService.USER_CONSUMER, user + ":2026-10"))
                .thenReturn(true, false);

        assertThat(service.closeMonth(user, OCTOBER)).isTrue();
        assertThat(service.closeMonth(user, OCTOBER)).isFalse();
    }

    @Test
    void aMonthThatHasNotFinishedYet_cannotBeClosed() {
        UserId user = new UserId(UUID.randomUUID());

        assertThatThrownBy(() -> service.closeMonth(user, YearMonth.of(2026, 11)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-11");
        assertThatThrownBy(() -> service.closeAll(YearMonth.of(2026, 11))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.closeAll(YearMonth.of(2027, 1))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(identity);
        verify(inbox, never()).firstTime(anyString(), anyString());
    }

    @Test
    void theRunningMonthIsDecidedInVietnamTime_notInUtc() {
        // 17:30 UTC on 31 October is already 00:30 on 1 November in Vietnam, so October is over there
        Clock lateOctoberUtc = Clock.fixed(Instant.parse("2026-10-31T17:30:00Z"), ZoneOffset.UTC);
        MonthCloseService vietnamNovember =
                new MonthCloseService(identity, ledger, periods, inbox, events, tx, lateOctoberUtc);
        UserId user = new UserId(UUID.randomUUID());

        assertThat(vietnamNovember.closeMonth(user, OCTOBER)).isTrue();

        Clock earlyOctoberUtc = Clock.fixed(Instant.parse("2026-10-31T16:30:00Z"), ZoneOffset.UTC);
        MonthCloseService vietnamOctober =
                new MonthCloseService(identity, ledger, periods, inbox, events, tx, earlyOctoberUtc);
        assertThatThrownBy(() -> vietnamOctober.closeMonth(user, OCTOBER)).isInstanceOf(IllegalArgumentException.class);
    }
}
