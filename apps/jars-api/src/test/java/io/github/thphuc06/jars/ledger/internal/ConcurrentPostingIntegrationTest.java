package io.github.thphuc06.jars.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.shared.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Money under concurrency on a real PostgreSQL 17 (ADR-0014): no lost update, no overspending, no deadlock, and the
 * invariant {@code jars.balance = SUM(jar_entries.amount)} holds afterwards. Calls the service directly from several
 * threads, released together by a latch to maximise the overlap.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConcurrentPostingIntegrationTest {

    @Autowired
    PostingService posting;

    @Autowired
    DefaultJarsProvisioner defaultJars;

    @Autowired
    JarRepository jars;

    @Autowired
    JdbcClient jdbc;

    UUID userId;

    @BeforeEach
    void userWithDefaultJars() {
        userId = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(userId, "sub-" + userId, userId + "@example.com")
                .update();
        defaultJars.ensureFor(userId);
    }

    @Test
    void twoExpensesRacingForTheSameMoney_onlyOneIsPaid() throws Exception {
        UUID play = fund(JarCode.PLAY, "100000");

        List<Outcome> outcomes = race(2, i -> () -> expense(play, "60000"));

        // without the version check both would read 100000, both pass the check and the jar would end at 40000 with
        // 120000 spent (lost update), or at -20000
        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.OK, Outcome.INSUFFICIENT);
        assertThat(balance(play)).isEqualByComparingTo("40000");
        assertThat(jarsWhoseBalanceDiffersFromTheirEntries()).isEmpty();
    }

    @Test
    void manySmallExpensesInParallel_noLostUpdate() throws Exception {
        UUID play = fund(JarCode.PLAY, "100000");
        int threads = 8;

        List<Outcome> outcomes = race(threads, i -> () -> expense(play, "1000"));

        long paid = outcomes.stream().filter(Outcome.OK::equals).count();
        // a request may give up after its retries (409 for the client) but it must never be half-applied
        assertThat(outcomes).isSubsetOf(Outcome.OK, Outcome.CONFLICT);
        assertThat(paid).isPositive();
        assertThat(balance(play)).isEqualByComparingTo(BigDecimal.valueOf(100000 - 1000 * paid));
        assertThat(spendEntries(play)).isEqualTo(paid);
        assertThat(jarsWhoseBalanceDiffersFromTheirEntries()).isEmpty();
    }

    @Test
    void oppositeTransfersInParallel_neverDeadlock_andMoneyIsConserved() throws Exception {
        UUID nec = fund(JarCode.NEC, "50000");
        UUID play = fund(JarCode.PLAY, "50000");

        // A→B and B→A at the same time is the classic deadlock: each transaction locks one row and waits for the other
        List<Outcome> outcomes = race(6, i -> i % 2 == 0 ? () -> transfer(nec, play) : () -> transfer(play, nec));

        assertThat(outcomes).isSubsetOf(Outcome.OK, Outcome.CONFLICT);
        assertThat(balance(nec).add(balance(play))).isEqualByComparingTo("100000");
        assertThat(jarsWhoseBalanceDiffersFromTheirEntries()).isEmpty();
    }

    // --- helpers ---

    enum Outcome {
        OK,
        INSUFFICIENT,
        CONFLICT
    }

    interface Task {
        Callable<Outcome> forThread(int index);
    }

    /** Runs one task per thread, all released at the same instant, and collects how each one ended. */
    private static List<Outcome> race(int threads, Task task) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                Callable<Outcome> work = task.forThread(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (ExecutionException e) {
                    throw new AssertionError("unexpected failure in a racing thread", e.getCause());
                }
            }
            return outcomes;
        }
    }

    private Outcome expense(UUID jarId, String amount) {
        return attempt(() -> posting.post(
                userId,
                new PostingService.PostTransaction(
                        TransactionType.EXPENSE, new BigDecimal(amount), jarId, null, null, null, null)));
    }

    private Outcome transfer(UUID from, UUID to) {
        return attempt(() ->
                posting.transfer(userId, new PostingService.Transfer(from, to, new BigDecimal("1000"), null, null)));
    }

    private static Outcome attempt(Runnable call) {
        try {
            call.run();
            return Outcome.OK;
        } catch (BusinessRuleViolationException e) {
            if ("insufficient-jar-balance".equals(e.code())) {
                return Outcome.INSUFFICIENT;
            }
            throw e;
        } catch (ConcurrencyFailureException e) {
            return Outcome.CONFLICT; // still conflicting after all retries
        }
    }

    /** Income into POOL, then allocate it to the jar: the normal way money reaches a jar. */
    private UUID fund(JarCode code, String amount) {
        UUID jarId = jars.findByUserIdAndCode(userId, code).orElseThrow().id();
        UUID poolId =
                jars.findByUserIdAndCode(userId, JarCode.POOL).orElseThrow().id();
        posting.post(
                userId,
                new PostingService.PostTransaction(
                        TransactionType.INCOME, new BigDecimal(amount), null, null, null, null, null));
        posting.transfer(userId, new PostingService.Transfer(poolId, jarId, new BigDecimal(amount), null, null));
        return jarId;
    }

    private BigDecimal balance(UUID jarId) {
        return jdbc.sql("SELECT balance FROM jars WHERE id = ?")
                .param(jarId)
                .query(BigDecimal.class)
                .single();
    }

    private long spendEntries(UUID jarId) {
        return jdbc.sql("SELECT count(*) FROM jar_entries WHERE jar_id = ? AND entry_type = 'SPEND'")
                .param(jarId)
                .query(Long.class)
                .single();
    }

    private List<UUID> jarsWhoseBalanceDiffersFromTheirEntries() {
        return jdbc.sql("""
                        SELECT j.id FROM jars j LEFT JOIN jar_entries e ON e.jar_id = j.id
                        WHERE j.user_id = ?
                        GROUP BY j.id, j.balance
                        HAVING j.balance <> COALESCE(SUM(e.amount), 0)
                        """).param(userId).query(UUID.class).list();
    }
}
