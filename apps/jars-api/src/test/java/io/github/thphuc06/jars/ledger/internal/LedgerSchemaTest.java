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

    // --- Flyway V4: transactions and jar_entries ---

    @Test
    void transactions_jarOfAnotherUser_isRejectedByTheCompositeForeignKey() {
        UUID mallory = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(mallory, "sub-" + mallory, mallory + "@example.com")
                .update();
        UUID victimJar = insertJar("PLAY", new BigDecimal("10"));

        // mallory's transaction pointing at the victim's jar: a bug in the code must not be able to store this
        assertThatThrownBy(() -> insertTransaction(mallory, "EXPENSE", victimJar, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_transactions_jar");
    }

    // one failing statement per test: after an error PostgreSQL aborts the rest of the (rolled back) test transaction

    @Test
    void transactions_transferWithoutDestination_isRejected() {
        UUID pool = insertJar("POOL", null);

        assertThatThrownBy(() -> insertTransaction(userId, "JAR_TRANSFER", pool, null))
                .hasMessageContaining("ck_transactions_transfer_target");
    }

    @Test
    void transactions_expenseWithDestination_isRejected() {
        UUID pool = insertJar("POOL", null);
        UUID play = insertJar("PLAY", new BigDecimal("10"));

        assertThatThrownBy(() -> insertTransaction(userId, "EXPENSE", play, pool))
                .hasMessageContaining("ck_transactions_transfer_target");
    }

    @Test
    void transactions_transferToTheSameJar_isRejected() {
        UUID play = insertJar("PLAY", new BigDecimal("10"));

        assertThatThrownBy(() -> insertTransaction(userId, "JAR_TRANSFER", play, play))
                .hasMessageContaining("ck_transactions_transfer_distinct_jars");
    }

    @Test
    void jarEntries_spendWithPositiveAmount_isRejected() {
        UUID play = insertJar("PLAY", new BigDecimal("10"));

        assertThatThrownBy(() -> insertEntry(play, "SPEND", new BigDecimal("1000")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_jar_entries_sign");
    }

    @Test
    void jarEntries_incomeWithNegativeAmount_isRejected() {
        UUID play = insertJar("PLAY", new BigDecimal("10"));

        assertThatThrownBy(() -> insertEntry(play, "INCOME", new BigDecimal("-1000")))
                .hasMessageContaining("ck_jar_entries_sign");
    }

    @Test
    void jarEntries_cannotBeUpdated() {
        UUID entry = insertEntry(insertJar("PLAY", new BigDecimal("10")), "INCOME", new BigDecimal("1000"));

        assertThatThrownBy(() -> jdbc.sql("UPDATE jar_entries SET amount = 999999 WHERE id = ?")
                        .param(entry)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void jarEntries_cannotBeDeleted() {
        UUID entry = insertEntry(insertJar("PLAY", new BigDecimal("10")), "INCOME", new BigDecimal("1000"));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM jar_entries WHERE id = ?")
                        .param(entry)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");
    }

    private UUID insertTransaction(UUID owner, String type, UUID jarId, UUID toJarId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO transactions (id, user_id, type, amount, jar_id, to_jar_id, occurred_at)
                        VALUES (?, ?, ?, 1000, ?, ?, now())
                        """).params(id, owner, type, jarId, toJarId).update();
        return id;
    }

    private UUID insertEntry(UUID jarId, String type, BigDecimal amount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO jar_entries (id, jar_id, entry_type, amount, balance_after) VALUES (?, ?, ?, ?, 0)")
                .params(id, jarId, type, amount)
                .update();
        return id;
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
