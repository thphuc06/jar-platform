package io.github.thphuc06.jars.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.shared.BusinessRuleViolationException;
import io.github.thphuc06.jars.shared.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Money Inbox on PostgreSQL 17: keyset pagination walks every row exactly once (also when several rows share the
 * same {@code occurredAt}), filters, and ONE SQL query per page. Hibernate statistics count the JDBC statements.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(TestcontainersConfiguration.class)
class TransactionQueriesIntegrationTest {

    static final UUID FOOD = UUID.fromString("01900000-0000-7000-8000-000000000001"); // EXPENSE, Flyway V3
    static final UUID FUN = UUID.fromString("01900000-0000-7000-8000-000000000008"); // EXPENSE
    static final UUID SALARY = UUID.fromString("01900000-0000-7000-8000-000000000101"); // INCOME
    static final TransactionQueries.Filter ALL = new TransactionQueries.Filter(null, null, null, null, null);

    @Autowired
    TransactionQueries queries;

    @Autowired
    PostingService posting;

    @Autowired
    DefaultJarsProvisioner defaultJars;

    @Autowired
    JarRepository jars;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TransactionTemplate transactionTemplate;

    UUID userId;
    UUID nec;
    UUID play;
    UUID pool;
    final Instant base = Instant.parse("2026-10-01T03:00:00Z");

    /** 1 income + 2 allocations + 22 expenses: 25 rows, the expenses three per minute so timestamps collide. */
    @BeforeEach
    void seed() {
        userId = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(userId, "sub-" + userId, userId + "@example.com")
                .update();
        defaultJars.ensureFor(userId);
        nec = jarId(JarCode.NEC);
        play = jarId(JarCode.PLAY);
        pool = jarId(JarCode.POOL);

        income("10000000", base);
        transfer(pool, nec, "5000000", base.plusSeconds(1));
        transfer(pool, play, "2000000", base.plusSeconds(2));
        for (int i = 0; i < 22; i++) {
            boolean even = i % 2 == 0;
            expense(even ? nec : play, even ? FOOD : FUN, "1000", base.plus(10 + i / 3, ChronoUnit.MINUTES));
        }
    }

    @Test
    void pages_visitEveryTransactionExactlyOnce_newestFirst() {
        List<TransactionDetails> walked = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            TransactionQueries.Page page = queries.list(userId, ALL, cursor, 10);
            walked.addAll(page.items());
            pageSizes.add(page.items().size());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(pageSizes).containsExactly(10, 10, 5);
        assertThat(walked)
                .extracting(TransactionDetails::id)
                .doesNotHaveDuplicates()
                .hasSize(25);
        assertThat(walked)
                .isSortedAccordingTo(Comparator.comparing(TransactionDetails::occurredAt)
                        .thenComparing(TransactionDetails::id)
                        .reversed());
    }

    @Test
    void onePage_isOneSqlQuery_thanksToTheEntityGraph() {
        Statistics statistics = statistics();
        statistics.clear();

        TransactionQueries.Page page = queries.list(userId, ALL, null, 25);

        assertThat(page.items()).hasSize(25);
        assertThat(page.items()).allSatisfy(t -> assertThat(t.jar().name()).isNotBlank());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    /** The same page WITHOUT the entity graph: every distinct lazy jar and category costs one more query (N+1). */
    @Test
    void withoutTheEntityGraph_readingTheNamesTriggersExtraQueries() {
        Statistics statistics = statistics();

        Long statements = transactionTemplate.execute(status -> {
            statistics.clear();
            entityManager
                    .createQuery(
                            "SELECT t FROM LedgerTransaction t WHERE t.userId = :userId ORDER BY t.occurredAt DESC",
                            LedgerTransaction.class)
                    .setParameter("userId", userId)
                    .setMaxResults(25)
                    .getResultList()
                    .forEach(TransactionDetails::of); // touches t.jar().name(), t.category().name() ...
            return statistics.getPrepareStatementCount();
        });

        // 1 for the page + POOL, NEC, PLAY + FOOD, FUN, SALARY: grows with the number of distinct references
        assertThat(statements).isGreaterThan(1).isEqualTo(7);
    }

    @Test
    void filterByJar_includesTransfersIntoThatJar() {
        var page = queries.list(userId, new TransactionQueries.Filter(play, null, null, null, null), null, 100);

        assertThat(page.items()).hasSize(12); // 11 expenses paid from PLAY + the allocation into PLAY
        assertThat(page.items())
                .filteredOn(t -> t.type() == TransactionType.JAR_TRANSFER)
                .hasSize(1);
    }

    @Test
    void filterByTypeCategoryAndTimeWindow() {
        assertThat(queries.list(
                                userId,
                                new TransactionQueries.Filter(null, null, TransactionType.INCOME, null, null),
                                null,
                                100)
                        .items())
                .hasSize(1);
        assertThat(queries.list(userId, new TransactionQueries.Filter(null, FOOD, null, null, null), null, 100)
                        .items())
                .hasSize(11);
        // [10:10, 10:12) holds the expenses of minute 10 and 11: 3 + 3
        Instant from = base.plus(10, ChronoUnit.MINUTES);
        assertThat(queries.list(
                                userId,
                                new TransactionQueries.Filter(null, null, null, from, from.plus(2, ChronoUnit.MINUTES)),
                                null,
                                100)
                        .items())
                .hasSize(6);
    }

    @Test
    void tamperedCursor_isRejected() {
        assertThatThrownBy(() -> queries.list(userId, ALL, "not-a-cursor", 10))
                .isInstanceOfSatisfying(
                        BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("invalid-cursor"));
    }

    @Test
    void otherUsers_neverSeeTheTransactions() {
        UUID someoneElse = UUID.randomUUID();
        UUID mine = queries.list(userId, ALL, null, 1).items().getFirst().id();

        assertThat(queries.list(someoneElse, ALL, null, 100).items()).isEmpty();
        assertThatThrownBy(() -> queries.get(someoneElse, mine)).isInstanceOf(NotFoundException.class);
    }

    // --- helpers ---

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private UUID jarId(JarCode code) {
        return jars.findByUserIdAndCode(userId, code).orElseThrow().id();
    }

    private void income(String amount, Instant at) {
        posting.post(
                userId,
                UUID.randomUUID(),
                new PostingService.PostTransaction(
                        TransactionType.INCOME, new BigDecimal(amount), null, SALARY, null, null, at));
    }

    private void expense(UUID jarId, UUID categoryId, String amount, Instant at) {
        posting.post(
                userId,
                UUID.randomUUID(),
                new PostingService.PostTransaction(
                        TransactionType.EXPENSE, new BigDecimal(amount), jarId, categoryId, null, null, at));
    }

    private void transfer(UUID from, UUID to, String amount, Instant at) {
        posting.transfer(
                userId, UUID.randomUUID(), new PostingService.Transfer(from, to, new BigDecimal(amount), null, at));
    }
}
