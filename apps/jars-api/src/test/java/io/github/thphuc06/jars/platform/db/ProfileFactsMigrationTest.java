package io.github.thphuc06.jars.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V12 and V13 on a database that already has users and questionnaire results (ADR-0027): migrate a fresh PostgreSQL to
 * V11, add users with assessments the way the app of that time did, then migrate to the latest version. Nobody is lost
 * or changed, the two new answers are simply "not answered yet", and the catalogue is there.
 */
class ProfileFactsMigrationTest {

    private static Flyway flyway(DataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas("jars")
                .defaultSchema("jars")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    void existingUsersWithAssessments_keepEverything_andHaveTheNewAnswersMissing() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            postgres.start();
            DataSource dataSource =
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcClient jdbc = JdbcClient.create(dataSource);

            flyway(dataSource, "11").migrate();
            UUID withAssessment = UUID.randomUUID();
            UUID withoutAssessment = UUID.randomUUID();
            jdbc.sql("INSERT INTO jars.users (id, cognito_sub, display_name) VALUES (?, 'old-1', 'Cũ Một')")
                    .param(withAssessment)
                    .update();
            jdbc.sql("INSERT INTO jars.users (id, cognito_sub) VALUES (?, 'old-2')")
                    .param(withoutAssessment)
                    .update();
            jdbc.sql("""
                            INSERT INTO jars.risk_assessments (id, user_id, answers, score, risk_level, assessed_at)
                            VALUES (?, ?, '[1,2,1,1,2]'::jsonb, 50, 'BALANCED', now())
                            """).params(UUID.randomUUID(), withAssessment).update();
            assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_name = 'catalog_products'")
                            .query(Integer.class)
                            .single())
                    .as("the catalogue does not exist before V12")
                    .isZero();

            flyway(dataSource, "13").migrate();

            // nobody lost, nothing changed, the new answers are not answered yet
            var users = jdbc.sql(
                            "SELECT cognito_sub, display_name, age_band, income_situation FROM jars.users ORDER BY cognito_sub")
                    .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)})
                    .list();
            assertThat(users).hasSize(2);
            assertThat(users.get(0)).containsExactly("old-1", "Cũ Một", null, null);
            assertThat(users.get(1)).containsExactly("old-2", null, null, null);
            assertThat(jdbc.sql("SELECT answers::text || ':' || score || ':' || risk_level FROM jars.risk_assessments")
                            .query(String.class)
                            .single())
                    .isEqualTo("[1, 2, 1, 1, 2]:50:BALANCED");
            // the catalogue is seeded
            assertThat(jdbc.sql("SELECT count(*) FROM jars.catalog_products")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(18);

            // the constraints hold: only the codes, never a free value
            assertThat(jdbc.sql(
                                    "UPDATE jars.users SET age_band = 'AGE_25_34', income_situation = 'NO_INCOME_YET' WHERE id = ?")
                            .param(withAssessment)
                            .update())
                    .isEqualTo(1);
            assertThatThrownBy(() -> jdbc.sql("UPDATE jars.users SET age_band = '1990-01-01' WHERE id = ?")
                            .param(withAssessment)
                            .update())
                    .hasMessageContaining("ck_users_age_band");
            assertThatThrownBy(() -> jdbc.sql("UPDATE jars.users SET income_situation = 'RICH' WHERE id = ?")
                            .param(withAssessment)
                            .update())
                    .hasMessageContaining("ck_users_income_situation");
        }
    }

    @Test
    void theCatalogueConstraintsRejectAnImpossibleRiskLevelOrGroup() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))) {
            postgres.start();
            DataSource dataSource =
                    new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcClient jdbc = JdbcClient.create(dataSource);
            flyway(dataSource, "13").migrate();

            assertThatThrownBy(() -> jdbc.sql("UPDATE jars.catalog_products SET risk_level = 6 WHERE code = 'LBF-EQ'")
                            .update())
                    .hasMessageContaining("ck_catalog_products_risk");
            assertThatThrownBy(() -> jdbc.sql(
                                    "UPDATE jars.catalog_products SET product_group = 'GOLD' WHERE code = 'LBF-EQ'")
                            .update())
                    .hasMessageContaining("ck_catalog_products_group");
            assertThatThrownBy(
                            () -> jdbc.sql("UPDATE jars.catalog_products SET risk_level_max = 1 WHERE code = 'LBF-EQ'")
                                    .update())
                    .hasMessageContaining("ck_catalog_products_risk");
        }
    }
}
