package io.github.thphuc06.jars.budgeting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.budgeting.internal.service.MonthCloseService;
import io.github.thphuc06.jars.ledger.TransactionPosted;
import io.github.thphuc06.jars.platform.security.UserDirectory;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

/**
 * The budgeting module on its own (with the modules it depends on, ledger and identity, but not notification or
 * insights): a ledger event updates the period, and closing a month publishes {@link MonthClosed}. Only the event
 * contracts are involved: no HTTP, and nobody listens to {@code MonthClosed} here.
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
class BudgetingModuleTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MonthCloseService months;

    @Autowired
    UserDirectory users;

    @Test
    void anExpenseEvent_addsToTheSpendingOfItsJarAndMonth(Scenario scenario) {
        UserId user = new UserId(UUID.randomUUID());
        UUID jar = UUID.randomUUID();
        TransactionPosted expense = new TransactionPosted(
                Uuids.v7(),
                UUID.randomUUID(),
                user,
                "EXPENSE",
                Money.vnd("85000"),
                jar,
                null,
                Instant.parse("2026-10-15T05:00:00Z"),
                "MANUAL");

        scenario.publish(expense)
                .andWaitForStateChange(() -> spent(jar), spent -> spent.signum() > 0)
                .andVerify(spent -> assertThat(spent).isEqualByComparingTo("85000"));
    }

    @Test
    void closingAMonth_publishesMonthClosedWithAnEntryPerActiveJar(Scenario scenario) {
        UserId user = users.resolve("module-" + UUID.randomUUID()); // the ledger gives a new user the six jars
        YearMonth month = YearMonth.now(VIETNAM).minusMonths(1);

        scenario.stimulate(() -> months.closeMonth(user, month))
                .andWaitForEventOfType(MonthClosed.class)
                .matching(e -> e.userId().equals(user))
                .toArriveAndVerify(e -> {
                    assertThat(e.month()).isEqualTo(month);
                    assertThat(e.summary()).hasSize(6);
                    assertThat(e.totalPlanned().isZero()).isTrue();
                    assertThat(e.totalSpent().isZero()).isTrue();
                    assertThat(e.eventId()).isNotNull();
                });
    }

    private BigDecimal spent(UUID jar) {
        return jdbc.sql("SELECT COALESCE(SUM(spent_amount), 0) FROM jar_periods WHERE jar_id = ?")
                .param(jar)
                .query(BigDecimal.class)
                .single();
    }
}
