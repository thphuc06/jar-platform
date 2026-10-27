package io.github.thphuc06.jars.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.shared.BusinessTime;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The seeder against PostgreSQL, through the real PostingService. Profile "local" is the default in tests. */
@SpringBootTest(properties = "jars.seed.email=Demo.Seed@Example.com")
@Import(TestcontainersConfiguration.class)
class DemoDataSeederIntegrationTest {

    @Autowired
    DemoDataSeeder seeder;

    @Autowired
    IdentityApi identity;

    @Autowired
    LedgerQueries queries;

    @Autowired
    JdbcClient jdbc;

    @Test
    void seedsTheUserOnce_andTheMoneyAddsUp() {
        // at startup the user did not exist yet: the runner only logged a warning
        UUID userId = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(userId, "sub-" + userId, "demo.seed@example.com")
                .update();
        assertThat(identity.findUserIdByEmail("DEMO.SEED@example.com ")).contains(userId); // trimmed, any case

        seeder.run(new DefaultApplicationArguments());

        int planned =
                DemoDataPlan.plan(userId, BusinessTime.today(Clock.systemUTC())).size();
        assertThat(count("SELECT count(*) FROM transactions WHERE user_id = ?", userId))
                .isEqualTo(planned);
        // total = income - expenses (transfers move money between jars only)
        BigDecimal net = jdbc.sql("""
                        SELECT COALESCE(SUM(CASE type WHEN 'INCOME' THEN amount WHEN 'EXPENSE' THEN -amount ELSE 0 END), 0)
                        FROM transactions WHERE user_id = ?""").param(userId).query(BigDecimal.class).single();
        assertThat(queries.jarsOf(userId).total()).isEqualByComparingTo(net);
        // every jar balance equals the sum of its entries (F2 invariant) and is never negative
        assertThat(count("""
                        SELECT count(*) FROM jars j
                        WHERE j.user_id = ? AND (j.balance < 0 OR j.balance <> COALESCE(
                            (SELECT SUM(e.amount) FROM jar_entries e WHERE e.jar_id = j.id), 0))""", userId)).isZero();

        assertThat(seeder.seed(userId)).as("second run").isZero();
        assertThat(count("SELECT count(*) FROM transactions WHERE user_id = ?", userId))
                .isEqualTo(planned);
    }

    private int count(String sql, UUID userId) {
        return jdbc.sql(sql).param(userId).query(Integer.class).single();
    }
}
