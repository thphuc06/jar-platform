package io.github.thphuc06.jars.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.identity.UserRegistered;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Module test: boots ONLY the ledger module (plus the shared modules) on PostgreSQL 17. The identity module is not
 * running; the test plays its role by publishing {@link UserRegistered} through a {@link Scenario}, which publishes
 * inside a transaction, so the outbox row is written and the listener fires after commit, like in production.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
class DefaultJarsProvisionerTests {

    static final String LISTENER_ID = "ledger.default-jars";

    @Autowired
    JarRepository jars;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FailedEventPublications failedPublications;

    @Autowired
    DefaultJarsProvisioner defaultJars;

    /** The ledger controllers need the identity module, which this test does not start: mock its public API. */
    @MockitoBean
    IdentityApi identity;

    @Test
    void userRegistered_createsTheSixJarsPlusPool_perBr1(Scenario scenario) {
        UUID userId = insertUser();

        scenario.publish(new UserRegistered(userId, "vi-VN"))
                .andWaitForStateChange(() -> jars.findByUserId(userId), found -> found.size() == 7)
                .andVerify(found -> {
                    assertThat(found)
                            .extracting(Jar::code)
                            .containsExactlyInAnyOrder(
                                    JarCode.NEC,
                                    JarCode.FFA,
                                    JarCode.LTSS,
                                    JarCode.EDU,
                                    JarCode.PLAY,
                                    JarCode.GIVE,
                                    JarCode.POOL);
                    assertThat(sumOfTargets(found)).isEqualByComparingTo("100");
                    assertThat(found)
                            .allSatisfy(jar -> assertThat(jar.balance()).isZero());
                });
    }

    @Test
    void successfulListener_deletesItsOutboxRow(Scenario scenario) {
        UUID userId = insertUser();

        scenario.publish(new UserRegistered(userId, "vi-VN"))
                .andWaitForStateChange(() -> outboxRows(userId), rows -> rows == 0)
                .andVerify(rows -> assertThat(jars.findByUserId(userId)).hasSize(7));
    }

    @Test
    void redeliveredEvent_doesNotCreateASecondSet(Scenario scenario) {
        UUID userId = insertUser();
        var event = new UserRegistered(userId, "vi-VN");
        scenario.publish(event).andWaitForStateChange(() -> outboxRows(userId), rows -> rows == 0);

        scenario.publish(event) // at-least-once delivery: the same event arrives a second time
                .andWaitForStateChange(() -> outboxRows(userId), rows -> rows == 0)
                .andVerify(rows -> assertThat(jars.findByUserId(userId)).hasSize(7));
    }

    /**
     * The outbox guarantee in one test: the listener fails (no users row yet, so the foreign key rejects the jars),
     * the event is NOT lost but kept as FAILED; once the cause is fixed, resubmitting it completes the work.
     */
    @Test
    void failingListener_keepsTheEvent_andResubmissionCompletesIt(Scenario scenario) {
        UUID userId = UUID.randomUUID(); // deliberately not inserted into users

        scenario.publish(new UserRegistered(userId, "vi-VN"))
                .andWaitForStateChange(() -> outboxStatus(userId), "FAILED"::equals)
                .andVerify(status -> assertThat(jars.existsByUserId(userId)).isFalse());

        insertUser(userId);
        scenario.stimulate(() -> failedPublications.resubmit(ResubmissionOptions.defaults()))
                .andWaitForStateChange(() -> outboxRows(userId), rows -> rows == 0)
                .andVerify(rows -> assertThat(jars.findByUserId(userId)).hasSize(7));
    }

    /**
     * The listener and a first GET /jars may create the jars at the same instant. Both read "no jars" before either
     * commits; with a check-then-insert the loser would hit uk_jars_user_code and fail. INSERT ... ON CONFLICT DO
     * NOTHING makes the loser wait for the winner's commit and then insert nothing.
     */
    @Test
    void concurrentCreation_neitherFailsNorDuplicates() throws Exception {
        UUID userId = insertUser();
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> created;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = IntStream.range(0, 2)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return defaultJars.ensureFor(userId);
                    }))
                    .toList();
            start.countDown();
            created = new ArrayList<>();
            for (Future<Integer> future : futures) {
                created.add(future.get());
            }
        }

        assertThat(created.stream().mapToInt(Integer::intValue).sum()).isEqualTo(7); // split between the two
        assertThat(jars.findByUserId(userId)).hasSize(7);
    }

    private static BigDecimal sumOfTargets(List<Jar> found) {
        return found.stream().map(Jar::targetPct).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long outboxRows(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE listener_id = ? AND serialized_event LIKE ?")
                .params(LISTENER_ID, "%" + userId + "%")
                .query(Long.class)
                .single();
    }

    private String outboxStatus(UUID userId) {
        return jdbc.sql("SELECT status FROM event_publication WHERE listener_id = ? AND serialized_event LIKE ?")
                .params(LISTENER_ID, "%" + userId + "%")
                .query(String.class)
                .optional()
                .orElse("NONE");
    }

    private UUID insertUser() {
        return insertUser(UUID.randomUUID());
    }

    private UUID insertUser(UUID id) {
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(id, "sub-" + id, id + "@example.com")
                .update();
        return id;
    }
}
