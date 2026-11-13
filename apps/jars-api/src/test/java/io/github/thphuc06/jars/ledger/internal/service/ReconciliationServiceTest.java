package io.github.thphuc06.jars.ledger.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.thphuc06.jars.ledger.internal.persistence.LedgerQueries;
import io.github.thphuc06.jars.ledger.internal.persistence.LedgerQueries.BalanceCheck;
import io.github.thphuc06.jars.ledger.internal.persistence.LedgerQueries.BalanceMismatch;
import io.github.thphuc06.jars.ledger.internal.service.ReconciliationService.ReconciliationReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/** What the reconciliation does with a result: logs, metrics and the transaction it reads in. No database. */
class ReconciliationServiceTest {

    private final LedgerQueries queries = mock(LedgerQueries.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ReconciliationService service = new ReconciliationService(queries, meters, transactions);

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(ReconciliationService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        serviceLogger.detachAppender(logs);
    }

    private static BalanceMismatch mismatch(String stored, String computed) {
        BigDecimal s = new BigDecimal(stored);
        BigDecimal c = new BigDecimal(computed);
        return new BalanceMismatch(UUID.randomUUID(), s, c, s.subtract(c));
    }

    private List<String> messages(Level level) {
        return logs.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void aCleanRun_logsOneInfoLineAndNoErrors_andCountsItAsClean() {
        when(queries.checkBalances(anyInt())).thenReturn(new BalanceCheck(120, 0, List.of()));

        ReconciliationReport report = service.reconcile();

        assertThat(report.clean()).isTrue();
        assertThat(report.jarsChecked()).isEqualTo(120);
        assertThat(messages(Level.ERROR)).isEmpty();
        assertThat(messages(Level.INFO))
                .singleElement()
                .asString()
                .contains("120 jars checked")
                .contains("0 mismatches")
                .contains(" ms");
        assertThat(meters.get("jars.ledger.reconciliation.runs")
                        .tag("result", "clean")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("jars.ledger.reconciliation.mismatches").gauge().value())
                .isZero();
        assertThat(meters.get("jars.ledger.reconciliation.jars.checked").gauge().value())
                .isEqualTo(120);
    }

    @Test
    void theGaugesDoNotExistBeforeTheFirstRun_soAFreshTaskNeverReportsAFalseZero() {
        assertThat(meters.find("jars.ledger.reconciliation.mismatches").gauge()).isNull();
        assertThat(meters.find("jars.ledger.reconciliation.jars.checked").gauge())
                .isNull();
    }

    @Test
    void everyMismatchGetsAnErrorLineWithJarIdAndAmounts() {
        BalanceMismatch m = mismatch("915123.45", "915000.00");
        when(queries.checkBalances(anyInt())).thenReturn(new BalanceCheck(7, 1, List.of(m)));

        service.reconcile();

        assertThat(messages(Level.ERROR))
                .singleElement()
                .asString()
                .contains("jar=" + m.jarId())
                .contains("stored=915123.45")
                .contains("computed=915000.00")
                .contains("difference=123.45");
        assertThat(messages(Level.INFO)).singleElement().asString().contains("1 mismatches");
        assertThat(meters.get("jars.ledger.reconciliation.runs")
                        .tag("result", "mismatch")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("jars.ledger.reconciliation.mismatches").gauge().value())
                .isEqualTo(1);
    }

    @Test
    void aSystemicBug_cannotFloodTheLog_butTheMetricHasTheFullCount() {
        List<BalanceMismatch> worst = new ArrayList<>();
        for (int i = 0; i < ReconciliationService.MAX_LOGGED_MISMATCHES; i++) {
            worst.add(mismatch("10.00", "0.00"));
        }
        when(queries.checkBalances(ReconciliationService.MAX_LOGGED_MISMATCHES))
                .thenReturn(new BalanceCheck(100_000, 80_000, worst));

        ReconciliationReport report = service.reconcile();

        assertThat(report.mismatches()).isEqualTo(80_000);
        // 50 jars plus one line saying how many were left out
        assertThat(messages(Level.ERROR)).hasSize(ReconciliationService.MAX_LOGGED_MISMATCHES + 1);
        assertThat(messages(Level.ERROR).getLast()).contains("79950 more jars").contains("limit 50");
        assertThat(meters.get("jars.ledger.reconciliation.mismatches").gauge().value())
                .isEqualTo(80_000);
    }

    @Test
    void theGaugeFollowsTheLastRun() {
        when(queries.checkBalances(anyInt()))
                .thenReturn(new BalanceCheck(10, 2, List.of(mismatch("1", "0"), mismatch("2", "0"))))
                .thenReturn(new BalanceCheck(10, 0, List.of()));

        service.reconcile();
        assertThat(meters.get("jars.ledger.reconciliation.mismatches").gauge().value())
                .isEqualTo(2);
        service.reconcile();
        assertThat(meters.get("jars.ledger.reconciliation.mismatches").gauge().value())
                .isZero();
        assertThat(meters.get("jars.ledger.reconciliation.runs").counters()).hasSize(2);
    }

    @Test
    void theCheckReadsInAReadOnlyTransaction() {
        when(queries.checkBalances(anyInt())).thenReturn(new BalanceCheck(1, 0, List.of()));

        service.reconcile();

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
    }

    @Test
    void aDatabaseFailure_isCountedAsAnError_andRethrownSoTheMessageIsDeliveredAgain() {
        when(queries.checkBalances(anyInt())).thenThrow(new IllegalStateException("connection refused"));

        assertThatThrownBy(service::reconcile).hasMessage("connection refused");

        assertThat(meters.get("jars.ledger.reconciliation.runs")
                        .tag("result", "error")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(messages(Level.ERROR)).singleElement().asString().contains("reconciliation failed");
        // a failed run says nothing about the ledger: it must not publish a "0 mismatches" gauge
        assertThat(meters.find("jars.ledger.reconciliation.mismatches").gauge()).isNull();
    }
}
