package io.github.thphuc06.jars.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Flyway V3 on PostgreSQL 17: the BR-1/BR-2/BR-3 rules the database itself enforces, as a last line of defence behind
 * the Java code. Each test runs in a transaction that is rolled back afterwards.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class LedgerSchemaTest {

    @Autowired
    JdbcClient jdbc;

    UUID userId;

    @BeforeEach
    void insertUser() {
        userId = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(userId, "sub-" + userId, userId + "@example.com")
                .update();
    }

    @Test
    void systemCategories_areSeededPerBr3() {
        Map<String, Long> perKind =
                jdbc.sql("SELECT kind FROM categories WHERE user_id IS NULL").query(String.class).stream()
                        .collect(Collectors.groupingBy(kind -> kind, Collectors.counting()));
        Long incomeNotToPool = jdbc.sql(
                        "SELECT count(*) FROM categories WHERE kind = 'INCOME' AND default_jar_code <> 'POOL'")
                .query(Long.class)
                .single();

        assertThat(perKind).containsExactlyInAnyOrderEntriesOf(Map.of("EXPENSE", 12L, "INCOME", 3L));
        assertThat(incomeNotToPool).isZero();
    }

    @Test
    void jars_secondJarWithTheSameStandardCode_isRejected() {
        insertJar("NEC", new BigDecimal("55"));

        assertThatThrownBy(() -> insertJar("NEC", new BigDecimal("10")))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_jars_user_code");
    }

    @Test
    void jars_customJarsMayRepeat() {
        insertJar("CUSTOM", BigDecimal.ZERO);
        insertJar("CUSTOM", BigDecimal.ZERO);

        assertThat(jdbc.sql("SELECT count(*) FROM jars WHERE user_id = ?")
                        .param(userId)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void jars_poolWithATargetPercentage_isRejected() {
        assertThatThrownBy(() -> insertJar("POOL", new BigDecimal("10")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_jars_target_pct_only_without_pool");
    }

    @Test
    void jars_standardJarWithoutTargetPercentage_isRejected() {
        assertThatThrownBy(() -> insertJar("NEC", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_jars_target_pct_only_without_pool");
    }

    @Test
    void jars_negativeBalance_isRejected() {
        UUID jarId = insertJar("PLAY", new BigDecimal("10"));

        assertThatThrownBy(() -> jdbc.sql("UPDATE jars SET balance = -1 WHERE id = ?")
                        .param(jarId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_jars_balance_not_negative");
    }

    @Test
    void jars_poolCannotBeArchived() {
        UUID poolId = insertJar("POOL", null);

        assertThatThrownBy(() -> jdbc.sql("UPDATE jars SET archived_at = now() WHERE id = ?")
                        .param(poolId)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_jars_pool_never_archived");
    }

    @Test
    void categories_expenseSuggestingPool_isRejected() {
        assertThatThrownBy(() -> insertCategory(userId, "Cà phê", "EXPENSE", "POOL"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_categories_expense_not_from_pool");
    }

    @Test
    void categories_duplicateSystemNameIgnoringCase_isRejected_thanksToNullsNotDistinct() {
        assertThatThrownBy(() -> insertCategory(null, "ĂN UỐNG", "EXPENSE", "NEC"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_categories_owner_kind_name");
    }

    @Test
    void categories_userMayReuseASystemName() {
        insertCategory(userId, "Ăn uống", "EXPENSE", "NEC"); // different owner, so no conflict
    }

    private UUID insertJar(String code, BigDecimal targetPct) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO jars (id, user_id, code, name, target_pct) VALUES (?, ?, ?, ?, ?)")
                .params(id, userId, code, code.toLowerCase(), targetPct)
                .update();
        return id;
    }

    private void insertCategory(UUID owner, String name, String kind, String jarCode) {
        jdbc.sql("INSERT INTO categories (id, user_id, name, kind, default_jar_code) VALUES (?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), owner, name, kind, jarCode)
                .update();
    }
}
