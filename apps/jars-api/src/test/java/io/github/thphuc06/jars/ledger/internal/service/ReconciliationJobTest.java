package io.github.thphuc06.jars.ledger.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.thphuc06.jars.ledger.internal.service.ReconciliationService.ReconciliationReport;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** What a message on the reconciliation queue means, and how it behaves when delivered again. */
class ReconciliationJobTest {

    private final ReconciliationService reconciliation = mock(ReconciliationService.class);
    private final ReconciliationJob job =
            new ReconciliationJob(reconciliation, JsonMapper.builder().build());

    private static final ReconciliationReport CLEAN = new ReconciliationReport(5, 0, List.of(), Duration.ofMillis(3));

    @Test
    void theScheduledMessage_runsTheReconciliation() {
        when(reconciliation.reconcile()).thenReturn(CLEAN);

        assertThat(job.handle("{\"job\":\"LEDGER_RECONCILIATION\"}")).isSameAs(CLEAN);

        verify(reconciliation).reconcile();
    }

    @Test
    void aRedeliveredMessage_justRunsTheReadOnlyCheckAgain() {
        when(reconciliation.reconcile()).thenReturn(CLEAN);

        job.handle("{\"job\":\"LEDGER_RECONCILIATION\"}");
        job.handle("{\"job\":\"LEDGER_RECONCILIATION\"}");

        verify(reconciliation, times(2)).reconcile();
    }

    @Test
    void foundMismatches_doNotFailTheMessage() {
        when(reconciliation.reconcile()).thenReturn(new ReconciliationReport(5, 3, List.of(), Duration.ofMillis(3)));

        ReconciliationReport report = job.handle("{\"job\":\"LEDGER_RECONCILIATION\"}");

        assertThat(report.mismatches()).isEqualTo(3); // returned normally: the message is acknowledged
    }

    @Test
    void aDatabaseFailure_failsTheMessage_soItIsDeliveredAgain() {
        when(reconciliation.reconcile()).thenThrow(new IllegalStateException("database is down"));

        assertThatThrownBy(() -> job.handle("{\"job\":\"LEDGER_RECONCILIATION\"}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"job\":\"MONTH_CLOSE\"}", "{}", "{\"job\":null}", "[]", "{\"month\":\"2026-10\"}"})
    void anotherJob_isIgnoredAndAcknowledged(String payload) {
        assertThat(job.handle(payload)).isNull();

        verifyNoInteractions(reconciliation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"job\":", "LEDGER_RECONCILIATION"})
    void aPayloadThatIsNotJson_isRejected_soTheQueueFinallyDeadLettersIt(String payload) {
        assertThatThrownBy(() -> job.handle(payload)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(reconciliation);
    }
}
