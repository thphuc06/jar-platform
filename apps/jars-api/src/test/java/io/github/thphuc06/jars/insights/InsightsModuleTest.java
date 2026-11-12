package io.github.thphuc06.jars.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.ledger.TransactionPosted;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

/**
 * The insights module on its own with Spring Modulith's {@link Scenario}: publish a ledger event, wait for the
 * module's published event. No HTTP and no other module's listeners are involved, only the event contract.
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration.class)
class InsightsModuleTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void anUnusualExpense_publishesAnAnomalyDetectedEvent(Scenario scenario) {
        UserId user = new UserId(UUID.randomUUID());
        UUID jar = UUID.randomUUID();

        // twelve ordinary expenses train the detector for this jar
        scenario.stimulate((tx, events) -> {
                    tx.executeWithoutResult(status -> {
                        for (int amount : new int[] {
                            95_000, 105_000, 98_000, 102_000, 100_000, 97_000, 103_000, 99_000, 101_000, 100_000,
                            96_000, 104_000
                        }) {
                            events.publishEvent(expense(user, jar, amount));
                        }
                    });
                })
                .andWaitForStateChange(() -> trainedExpenses(jar), n -> n == 12);

        scenario.publish(expense(user, jar, 2_000_000))
                .andWaitForEventOfType(AnomalyDetected.class)
                .matching(e -> e.jarId().equals(jar))
                .toArriveAndVerify(e -> {
                    assertThat(e.detector()).isEqualTo("MAD");
                    assertThat(e.direction()).isEqualTo("UP");
                    assertThat(e.severity()).isEqualTo("HIGH");
                    assertThat(e.userId()).isEqualTo(user);
                    assertThat(e.amount()).isEqualTo(Money.vnd(2_000_000));
                    assertThat(e.jarName())
                            .as("the jar is unknown to the ledger, so a neutral name is used")
                            .isEqualTo("Hũ");
                });
    }

    @Test
    void ordinaryExpenses_publishNoEvent(Scenario scenario) {
        UserId user = new UserId(UUID.randomUUID());
        UUID jar = UUID.randomUUID();

        scenario.stimulate((tx, events) -> {
                    tx.executeWithoutResult(status -> {
                        for (int i = 0; i < 15; i++) {
                            events.publishEvent(expense(user, jar, 100_000 + (i % 3) * 1_000));
                        }
                    });
                })
                .andWaitForStateChange(() -> trainedExpenses(jar), n -> n == 15)
                .andVerify(n -> assertThat(anomalyCount(jar)).isZero());
    }

    private Integer trainedExpenses(UUID jar) {
        return jdbc.sql("SELECT COALESCE(SUM(txn_count), 0) FROM monthly_jar_spend WHERE jar_id = ?")
                .param(jar)
                .query(Integer.class)
                .single();
    }

    private Integer anomalyCount(UUID jar) {
        return jdbc.sql("SELECT count(*) FROM anomalies WHERE jar_id = ?")
                .param(jar)
                .query(Integer.class)
                .single();
    }

    private static TransactionPosted expense(UserId user, UUID jar, int amount) {
        return new TransactionPosted(
                Uuids.v7(), UUID.randomUUID(), user, "EXPENSE", Money.vnd(amount), jar, null, Instant.now(), "MANUAL");
    }
}
