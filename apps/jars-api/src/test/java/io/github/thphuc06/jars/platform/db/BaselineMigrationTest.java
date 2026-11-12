package io.github.thphuc06.jars.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Runs the real Flyway migrations against PostgreSQL 17 (Testcontainers) and checks the constraints
 * the application will rely on. Each test runs in a transaction that is rolled back afterwards.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class BaselineMigrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void flyway_appliesV1IntoJarsSchema() {
        String schema = jdbc.sql("SELECT current_schema()").query(String.class).single();
        Boolean v1Applied = jdbc.sql("SELECT success FROM jars.flyway_schema_history WHERE version = '1'")
                .query(Boolean.class)
                .single();

        assertThat(schema).isEqualTo("jars");
        assertThat(v1Applied).isTrue();
    }

    @Test
    void users_defaultsLocaleAndTimestamps() {
        UUID id = insertUser("sub-1", "an@example.com");

        var row = jdbc.sql("SELECT locale, created_at IS NOT NULL AS has_created FROM users WHERE id = ?")
                .param(id)
                .query((rs, n) -> new Object[] {rs.getString("locale"), rs.getBoolean("has_created")})
                .single();

        assertThat(row[0]).isEqualTo("vi-VN");
        assertThat(row[1]).isEqualTo(true);
    }

    @Test
    void users_rejectsDuplicateCognitoSub() {
        insertUser("sub-dup", "a@example.com");

        assertThatThrownBy(() -> insertUser("sub-dup", "b@example.com"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_users_cognito_sub");
    }

    @Test
    void users_allowTheSameEmailUnderDifferentSubjects() {
        // V3: identity is the Cognito sub only, so re-registering with the same e-mail must not fail
        insertUser("sub-a", "An@Example.com");

        UUID second = insertUser("sub-b", "an@example.com");

        assertThat(second).isNotNull();
    }

    @Test
    void users_allowAMissingEmail() {
        jdbc.sql("INSERT INTO users (id, cognito_sub) VALUES (?, ?)")
                .params(UUID.randomUUID(), "sub-no-email")
                .update();

        Integer count = jdbc.sql("SELECT count(*) FROM users WHERE cognito_sub = 'sub-no-email' AND email IS NULL")
                .query(Integer.class)
                .single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void riskAssessments_rejectScoresOutsideZeroToHundred() {
        UUID userId = insertUser("sub-risk", null);

        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO risk_assessments (id, user_id, answers, score, risk_level, assessed_at)
                                VALUES (?, ?, '[0,0,0,0,0]'::jsonb, 101, 'GROWTH', now())
                                """).params(UUID.randomUUID(), userId).update())
                .hasMessageContaining("ck_risk_assessments_score");
    }

    @Test
    void riskAssessments_rejectUnknownLevels() {
        UUID userId = insertUser("sub-risk-2", null);

        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO risk_assessments (id, user_id, answers, score, risk_level, assessed_at)
                                VALUES (?, ?, '[0,0,0,0,0]'::jsonb, 50, 'YOLO', now())
                                """).params(UUID.randomUUID(), userId).update())
                .hasMessageContaining("ck_risk_assessments_level");
    }

    private UUID insertUser(String cognitoSub, String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(id, cognitoSub, email)
                .update();
        return id;
    }
}
