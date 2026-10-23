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
    void users_rejectsSameEmailWithDifferentCase() {
        insertUser("sub-a", "An@Example.com");

        assertThatThrownBy(() -> insertUser("sub-b", "an@example.COM"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_users_email_lower");
    }

    private UUID insertUser(String cognitoSub, String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(id, cognitoSub, email)
                .update();
        return id;
    }
}
