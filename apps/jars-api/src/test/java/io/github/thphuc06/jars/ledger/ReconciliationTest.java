package io.github.thphuc06.jars.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.thphuc06.jars.ApiTestSupport;
import io.github.thphuc06.jars.ledger.internal.persistence.LedgerQueries;
import io.github.thphuc06.jars.ledger.internal.persistence.LedgerQueries.BalanceMismatch;
import io.github.thphuc06.jars.ledger.internal.service.ReconciliationJob;
import io.github.thphuc06.jars.ledger.internal.service.ReconciliationService;
import io.github.thphuc06.jars.ledger.internal.service.ReconciliationService.ReconciliationReport;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/**
 * The nightly reconciliation against a real PostgreSQL: the invariant {@code jars.balance == SUM(jar_entries.amount)}
 * holds after real postings, and the job finds the jars where it does not. Corruptions are made with plain SQL and
 * always undone, because the whole application context (and its database) is shared with other tests, which also
 * run the reconciliation implicitly through their assertions.
 */
class ReconciliationTest extends ApiTestSupport {

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private ReconciliationJob job;

    @Autowired
    private LedgerQueries queries;

    @Autowired
    private MeterRegistry meters;

    @Test
    void afterNormalPostingsAndTransfers_thereAreNoMismatches() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID edu = jarId(user, "EDU");
        post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "2000000"));
        spend(user, nec, "85000");
        spend(user, edu, "30000"); // an overdrawn jar is still consistent
        assertThat(post(user, "/api/v1/jars/transfers", newKey(), transferBody(nec, edu, "300000"))
                        .status())
                .isEqualTo(201);

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(report.mismatches()).isZero();
        assertThat(report.clean()).isTrue();
        assertThat(report.reported()).isEmpty();
        assertThat(report.jarsChecked()).isGreaterThanOrEqualTo(6);
        assertThat(report.took()).isNotNull().isPositive();
        assertThat(gauge("jars.ledger.reconciliation.mismatches")).isZero();
        assertThat(gauge("jars.ledger.reconciliation.jars.checked")).isEqualTo(report.jarsChecked());
    }

    @Test
    void aJarWithoutEntriesAndZeroBalance_isClean() {
        JwtRequestPostProcessor user = newUser();
        UUID play = jarId(user, "PLAY"); // the six default jars start with no entries and balance 0

        assertThat(entriesCount(play)).isZero();
        assertThat(balanceOf(play)).isEqualByComparingTo("0");

        assertThat(reconciliation.reconcile().reported())
                .extracting(BalanceMismatch::jarId)
                .doesNotContain(play);
    }

    @Test
    void aCorruptedBalance_isReportedWithStoredComputedAndDifference_andNothingIsCorrected() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "1000000"));
        spend(user, nec, "85000"); // entries: +1000000.00 -85000.00 = 915000.00
        double cleanRuns = runs("clean");
        double mismatchRuns = runs("mismatch");

        setBalance(nec, "915123.45");
        try {
            ReconciliationReport report = reconciliation.reconcile();

            assertThat(report.mismatches()).isEqualTo(1);
            assertThat(report.reported()).hasSize(1);
            BalanceMismatch mismatch = report.reported().get(0);
            assertThat(mismatch.jarId()).isEqualTo(nec);
            assertThat(mismatch.stored()).isEqualByComparingTo("915123.45");
            assertThat(mismatch.computed()).isEqualByComparingTo("915000.00");
            assertThat(mismatch.difference()).isEqualByComparingTo("123.45");
            assertThat(gauge("jars.ledger.reconciliation.mismatches")).isEqualTo(1);
            assertThat(runs("mismatch")).isEqualTo(mismatchRuns + 1);
            assertThat(runs("clean")).isEqualTo(cleanRuns);
            // detect and report only: the wrong balance is still there
            assertThat(balanceOf(nec)).isEqualByComparingTo("915123.45");
        } finally {
            setBalance(nec, "915000.00");
        }

        ReconciliationReport after = reconciliation.reconcile();
        assertThat(after.mismatches()).isZero();
        assertThat(gauge("jars.ledger.reconciliation.mismatches"))
                .as("the gauge follows the last run, so the alarm clears by itself once the data is fixed")
                .isZero();
        assertThat(runs("clean")).isEqualTo(cleanRuns + 1);
    }

    @Test
    void aJarWithoutEntriesAndANonZeroBalance_isReported() {
        JwtRequestPostProcessor user = newUser();
        UUID play = jarId(user, "PLAY");

        setBalance(play, "10.00");
        try {
            ReconciliationReport report = reconciliation.reconcile();

            assertThat(report.reported()).hasSize(1);
            BalanceMismatch mismatch = report.reported().get(0);
            assertThat(mismatch.jarId()).isEqualTo(play);
            assertThat(mismatch.stored()).isEqualByComparingTo("10.00");
            assertThat(mismatch.computed()).isEqualByComparingTo("0");
            assertThat(mismatch.difference()).isEqualByComparingTo("10.00");
        } finally {
            setBalance(play, "0.00");
        }
    }

    @Test
    void mismatchesOfBothSigns_andArchivedJars_areFound_largestDifferenceFirst() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID edu = jarId(user, "EDU");
        UUID play = jarId(user, "PLAY");
        post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "500000"));

        setBalance(nec, "499950.00"); // 50 too low
        setBalance(edu, "-7.50"); // 7.50 too low, no entries at all
        setBalance(play, "1000.00"); // 1000 too high ...
        jdbc.sql("UPDATE jars SET archived_at = now() WHERE id = ?").param(play).update(); // ... in an archived jar
        try {
            ReconciliationReport report = reconciliation.reconcile();

            assertThat(report.mismatches()).isEqualTo(3);
            assertThat(report.reported())
                    .extracting(BalanceMismatch::jarId, m -> m.difference().toPlainString())
                    .containsExactly(tuple(play, "1000.00"), tuple(nec, "-50.00"), tuple(edu, "-7.50"));
            assertThat(gauge("jars.ledger.reconciliation.mismatches")).isEqualTo(3);
        } finally {
            setBalance(nec, "500000.00");
            setBalance(edu, "0.00");
            setBalance(play, "0.00");
            jdbc.sql("UPDATE jars SET archived_at = NULL WHERE id = ?")
                    .param(play)
                    .update();
        }
        assertThat(reconciliation.reconcile().mismatches()).isZero();
    }

    @Test
    void theQuery_countsAllMismatchesButReturnsOnlyTheRequestedNumberOfTheWorst() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID edu = jarId(user, "EDU");
        setBalance(nec, "5.00");
        setBalance(edu, "-9.00");
        try {
            LedgerQueries.BalanceCheck check = queries.checkBalances(1);

            assertThat(check.mismatches()).isEqualTo(2);
            assertThat(check.worst()).hasSize(1);
            assertThat(check.worst().get(0).jarId()).isEqualTo(edu); // |-9| > |5|
            assertThat(check.jarsChecked()).isGreaterThanOrEqualTo(6);
        } finally {
            setBalance(nec, "0.00");
            setBalance(edu, "0.00");
        }
    }

    @Test
    void theJobMessageRunsTheCheck() {
        ReconciliationReport report = job.handle("{\"job\":\"LEDGER_RECONCILIATION\"}");

        assertThat(report).isNotNull();
        assertThat(report.mismatches()).isZero();
    }

    private void setBalance(UUID jarId, String balance) {
        jdbc.sql("UPDATE jars SET balance = ? WHERE id = ?")
                .param(new BigDecimal(balance))
                .param(jarId)
                .update();
    }

    private int entriesCount(UUID jarId) {
        return jdbc.sql("SELECT count(*) FROM jar_entries WHERE jar_id = ?")
                .param(jarId)
                .query(Integer.class)
                .single();
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private double runs(String result) {
        var counter = meters.find("jars.ledger.reconciliation.runs")
                .tag("result", result)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
