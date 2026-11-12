package io.github.thphuc06.jars.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.ProblemTypes;
import io.github.thphuc06.jars.shared.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyServiceTest {

    record Receipt(String id) {}

    @Autowired
    IdempotencyService service;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    private final UserId user = new UserId(UUID.randomUUID());

    @Test
    void firstCall_runsTheActionAndStoresTheResponse() {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();

        var result = call(key, "h1", runs, "A");

        assertThat(result.replayed()).isFalse();
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body()).isEqualTo(new Receipt("A-1"));
        assertThat(runs).hasValue(1);
    }

    @Test
    void sameKeyAndSameRequest_replaysTheStoredResponseWithoutRunningTheActionAgain() {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();
        call(key, "h1", runs, "A");

        var second = call(key, "h1", runs, "B");

        assertThat(second.replayed()).isTrue();
        assertThat(second.body()).isEqualTo(new Receipt("A-1")); // the first response, not "B-2"
        assertThat(second.status()).isEqualTo(201);
        assertThat(runs).hasValue(1);
    }

    @Test
    void sameKeyWithADifferentRequest_isRejected() {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();
        call(key, "h1", runs, "A");

        assertThatThrownBy(() -> call(key, "OTHER-HASH", runs, "A"))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> assertThat(e.type()).isEqualTo(ProblemTypes.IDEMPOTENCY_KEY_REUSED));
        assertThat(runs).hasValue(1);
    }

    @Test
    void sameKeyForADifferentUser_isIndependent() {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();
        call(key, "h1", runs, "A");

        var other = tx.execute(s -> service.execute(
                new UserId(UUID.randomUUID()),
                key,
                "h1",
                Receipt.class,
                201,
                () -> new Receipt("B-" + runs.incrementAndGet())));

        assertThat(other.replayed()).isFalse();
        assertThat(runs).hasValue(2);
    }

    @Test
    void invalidKey_isRejectedBeforeAnythingIsWritten() {
        for (String bad : new String[] {null, "", "short", "has spaces in it!", "x".repeat(200)}) {
            assertThatThrownBy(() -> call(bad, "h", new AtomicInteger(), "A"))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            e -> assertThat(e.type()).isEqualTo(ProblemTypes.IDEMPOTENCY_KEY_INVALID));
        }
    }

    @Test
    void failedAction_rollsBackTheKeySoTheClientCanRetryWithTheSameKey() {
        String key = newKey();

        assertThatThrownBy(() -> tx.execute(s -> service.execute(user, key, "h1", Receipt.class, 201, () -> {
                    throw new IllegalStateException("boom");
                })))
                .isInstanceOf(IllegalStateException.class);

        AtomicInteger runs = new AtomicInteger();
        var retry = call(key, "h1", runs, "A");
        assertThat(retry.replayed()).isFalse();
        assertThat(runs).hasValue(1);
    }

    @Test
    void mustRunInsideATransaction() {
        assertThatThrownBy(() -> service.execute(user, newKey(), "h", Receipt.class, 201, () -> new Receipt("x")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void concurrentRequestsWithTheSameKey_runTheActionExactlyOnce() throws Exception {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<IdempotencyService.Result<Receipt>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<IdempotencyService.Result<Receipt>> task = () -> {
                    start.await();
                    return tx.execute(s -> service.execute(user, key, "same", Receipt.class, 201, () -> {
                        sleep(150); // keep the first transaction open so the others really have to wait for it
                        return new Receipt("run-" + runs.incrementAndGet());
                    }));
                };
                futures.add(pool.submit(task));
            }
            start.countDown();

            List<IdempotencyService.Result<Receipt>> results = new ArrayList<>();
            for (var f : futures) {
                results.add(f.get());
            }

            assertThat(runs).hasValue(1);
            assertThat(results).extracting(r -> r.body().id()).containsOnly("run-1");
            assertThat(results).filteredOn(IdempotencyService.Result::replayed).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void purgeExpired_removesOnlyKeysPastTheirExpiry() {
        String fresh = newKey();
        String stale = newKey();
        call(fresh, "h", new AtomicInteger(), "A");
        call(stale, "h", new AtomicInteger(), "A");
        jdbc.sql("UPDATE idempotency_keys SET expires_at = now() - interval '1 hour' WHERE key = ?")
                .param(stale)
                .update();

        int deleted = service.purgeExpired();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(count(stale)).isZero();
        assertThat(count(fresh)).isEqualTo(1);
    }

    @Test
    void hash_isStableAndSensitiveToEveryPart() {
        assertThat(IdempotencyService.hash("POST", "/a", "{}")).isEqualTo(IdempotencyService.hash("POST", "/a", "{}"));
        assertThat(IdempotencyService.hash("POST", "/a", "{}"))
                .isNotEqualTo(IdempotencyService.hash("POST", "/a", "{ }"));
        // the separator stops ("ab","c") and ("a","bc") from colliding
        assertThat(IdempotencyService.hash("ab", "c")).isNotEqualTo(IdempotencyService.hash("a", "bc"));
    }

    private IdempotencyService.Result<Receipt> call(String key, String hash, AtomicInteger runs, String label) {
        return tx.execute(s -> service.execute(
                user, key, hash, Receipt.class, 201, () -> new Receipt(label + "-" + runs.incrementAndGet())));
    }

    private int count(String key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE key = ?")
                .param(key)
                .query(Integer.class)
                .single();
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
