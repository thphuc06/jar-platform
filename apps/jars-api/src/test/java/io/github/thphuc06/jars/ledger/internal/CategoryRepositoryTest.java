package io.github.thphuc06.jars.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** JPA mapping of {@link Category} against Flyway V3 (ddl-auto=validate) and the per-user visibility rule. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class CategoryRepositoryTest {

    @Autowired
    CategoryRepository categories;

    @Autowired
    JdbcClient jdbc;

    @Test
    void findVisibleTo_returnsSystemDefaultsAndOwnCategories_neverAnotherUsers() {
        UUID me = insertUser();
        UUID someoneElse = insertUser();
        insertCategory(me, "Cà phê");
        insertCategory(someoneElse, "Bí mật của người khác");

        var visible = categories.findVisibleTo(me);

        assertThat(visible).hasSize(16); // 15 system + 1 own
        assertThat(visible).extracting(Category::name).contains("Ăn uống", "Lương", "Cà phê");
        assertThat(visible).extracting(Category::name).doesNotContain("Bí mật của người khác");
    }

    @Test
    void systemCategory_mapsKindAndSuggestedJar() {
        Category salary = categories
                .findById(UUID.fromString("01900000-0000-7000-8000-000000000101"))
                .orElseThrow();

        assertThat(salary.userId()).isNull();
        assertThat(salary.kind()).isEqualTo(CategoryKind.INCOME);
        assertThat(salary.defaultJarCode()).isEqualTo(JarCode.POOL);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(id, "sub-" + id, id + "@example.com")
                .update();
        return id;
    }

    private void insertCategory(UUID owner, String name) {
        jdbc.sql(
                        "INSERT INTO categories (id, user_id, name, kind, default_jar_code) VALUES (?, ?, ?, 'EXPENSE', 'NEC')")
                .params(UUID.randomUUID(), owner, name)
                .update();
    }
}
