package io.github.thphuc06.jars.ledger.internal.dev;

import io.github.thphuc06.jars.ledger.internal.domain.TransactionType;
import io.github.thphuc06.jars.ledger.internal.service.LedgerCommandService;
import io.github.thphuc06.jars.ledger.internal.service.LedgerCommandService.PostTransactionCommand;
import io.github.thphuc06.jars.ledger.internal.service.LedgerQueryService;
import io.github.thphuc06.jars.ledger.internal.service.Views.JarView;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService;
import io.github.thphuc06.jars.platform.security.UserDirectory;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Demo data for a developer machine: a user whose token subject is {@code demo} (see {@code POST /dev/token}) with three
 * months of income and everyday expenses in the six jars, followed by two unusual purchases so the anomaly alerts, the
 * dashboard and the live notifications have something to show. Enabled with {@code --jars.seed.enabled=true} and only in
 * the {@code local} profile; it does nothing when the demo user already has transactions, so it is safe to leave on.
 *
 * <p>It lives in the ledger module because it needs the ledger's own services; it creates the user through the same
 * lazy-provisioning path a real first request takes.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "jars.seed.enabled", havingValue = "true")
class SeedDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

    static final String DEMO_SUBJECT = "demo";
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int HISTORY_DAYS = 90;

    // ids of the system categories created by V4__ledger.sql
    private static final UUID FOOD = category("01");
    private static final UUID GROCERIES = category("02");
    private static final UUID TRANSPORT = category("03");
    private static final UUID BILLS = category("04");
    private static final UUID RENT = category("05");
    private static final UUID EDUCATION = category("07");
    private static final UUID ENTERTAINMENT = category("08");
    private static final UUID SHOPPING = category("09");
    private static final UUID GIFTS = category("0d");
    private static final UUID SALARY = category("101");
    private static final UUID BONUS = category("103");

    private final UserDirectory users;
    private final LedgerCommandService commands;
    private final LedgerQueryService queries;
    private final JdbcClient jdbc;
    private final Clock clock;

    SeedDataRunner(
            UserDirectory users,
            LedgerCommandService commands,
            LedgerQueryService queries,
            JdbcClient jdbc,
            Clock clock) {
        this.users = users;
        this.commands = commands;
        this.queries = queries;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Seeds in the background so the application is reachable immediately. */
    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("seed-data").start(() -> {
            try {
                seed();
            } catch (RuntimeException e) {
                log.error("Seeding demo data failed", e);
            }
        });
    }

    void seed() {
        UserId demo = users.resolve(DEMO_SUBJECT);
        if (!queries.transactions(demo, null, null, null, null, null, 1, null)
                .items()
                .isEmpty()) {
            log.info("Demo data already present for subject '{}', nothing to do", DEMO_SUBJECT);
            return;
        }
        Map<String, UUID> jars = new HashMap<>();
        for (JarView jar : queries.jars(demo)) {
            jars.put(jar.code(), jar.id());
        }
        // rent gets its own jar: a large monthly bill would look like an outlier next to daily food and taxi money
        jars.put("RENT", commands.createJar(demo, "Tiền nhà", BigDecimal.ZERO).id());
        LocalDate today = LocalDate.now(clock.withZone(VIETNAM));
        Random random = new Random(42); // fixed seed: everyone's demo looks the same
        int[] counter = {0};

        for (int back = HISTORY_DAYS; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            if (day.getDayOfMonth() == 1) {
                income(demo, jars, day, counter);
            }
            dailyExpenses(demo, jars, day, random, counter);
        }
        log.info("Seeded {} historical postings, waiting for the detectors to learn them", counter[0]);
        awaitEventsProcessed(Duration.ofSeconds(90));

        // two purchases far outside what these jars normally see (the detector works per jar, so ordinary spending of
        // a`n        // similar size, as above, raises no alert)
        expense(demo, jars.get("PLAY"), 3_200_000, SHOPPING, "Mua loa bluetooth", today.minusDays(1), 18, counter);
        expense(demo, jars.get("NEC"), 2_500_000, BILLS, "Sửa xe máy", today.minusDays(1), 9, counter);
        post(
                demo,
                TransactionType.INCOME,
                jars.get("FFA"),
                2_000_000,
                BONUS,
                "Thưởng dự án",
                today.minusDays(1),
                10,
                counter);
        log.info("Demo data ready: {} postings for subject '{}'", counter[0], DEMO_SUBJECT);
    }

    private void income(UserId user, Map<String, UUID> jars, LocalDate day, int[] counter) {
        long salary = 15_000_000;
        // the salary is split over the jars by their default shares: 55 / 10 / 10 / 10 / 10 / 5 %
        long[] parts = {salary * 55 / 100, salary / 10, salary / 10, salary / 10, salary / 10, salary * 5 / 100};
        String[] codes = {"NEC", "FFA", "LTSS", "EDU", "PLAY", "GIVE"};
        for (int i = 0; i < codes.length; i++) {
            post(
                    user,
                    TransactionType.INCOME,
                    jars.get(codes[i]),
                    parts[i],
                    SALARY,
                    "Lương tháng " + day.getMonthValue(),
                    day,
                    8,
                    counter);
        }
        transfer(user, jars.get("NEC"), jars.get("RENT"), 4_500_000, day, counter);
        expense(user, jars.get("RENT"), 4_500_000, RENT, "Tiền thuê nhà", day, 9, counter);
    }

    private void dailyExpenses(UserId user, Map<String, UUID> jars, LocalDate day, Random random, int[] counter) {
        boolean weekend = day.getDayOfWeek().getValue() >= 6;
        expense(user, jars.get("NEC"), round(random, 40_000, 110_000), FOOD, "Ăn trưa", day, 12, counter);
        if (random.nextInt(3) == 0) {
            expense(user, jars.get("NEC"), round(random, 25_000, 90_000), TRANSPORT, "Grab", day, 18, counter);
        }
        if (day.getDayOfWeek().getValue() == 7) {
            expense(
                    user,
                    jars.get("NEC"),
                    round(random, 70_000, 130_000),
                    GROCERIES,
                    "Đi chợ cuối tuần",
                    day,
                    10,
                    counter);
        }
        if (weekend) {
            expense(
                    user,
                    jars.get("PLAY"),
                    round(random, 80_000, 220_000),
                    ENTERTAINMENT,
                    "Cà phê, xem phim",
                    day,
                    20,
                    counter);
        }
        if (day.getDayOfMonth() == 5) {
            expense(
                    user,
                    jars.get("EDU"),
                    round(random, 250_000, 450_000),
                    EDUCATION,
                    "Khóa học online",
                    day,
                    21,
                    counter);
        }
        if (day.getDayOfMonth() == 15) {
            expense(user, jars.get("GIVE"), round(random, 100_000, 300_000), GIFTS, "Quà tặng", day, 19, counter);
        }
    }

    private void transfer(UserId user, UUID from, UUID to, long amount, LocalDate day, int[] counter) {
        String key = "seed-%05d".formatted(++counter[0]);
        commands.transfer(
                user,
                key,
                IdempotencyService.hash("seed", key),
                new LedgerCommandService.TransferCommand(from, to, Money.vnd(amount), "Chuẩn bị tiền nhà"));
    }

    private void expense(
            UserId user, UUID jar, long amount, UUID category, String what, LocalDate day, int hour, int[] counter) {
        post(user, TransactionType.EXPENSE, jar, amount, category, what, day, hour, counter);
    }

    private void post(
            UserId user,
            TransactionType type,
            UUID jar,
            long amount,
            UUID category,
            String note,
            LocalDate day,
            int hour,
            int[] counter) {
        Instant at = day.atTime(LocalTime.of(hour, 0)).atZone(VIETNAM).toInstant();
        PostTransactionCommand cmd = new PostTransactionCommand(type, Money.vnd(amount), jar, category, null, note, at);
        String key = "seed-%05d".formatted(++counter[0]);
        commands.post(user, key, IdempotencyService.hash("seed", key), cmd);
    }

    /** Rows stay in the event publication registry until their listener has finished, so an empty registry means "caught up". */
    private void awaitEventsProcessed(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Integer pending = jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                    .query(Integer.class)
                    .single();
            if (pending == 0) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Timed out waiting for the event listeners; detectors may not have seen every posting");
    }

    private static long round(Random random, int low, int high) {
        return Math.round((low + random.nextInt(high - low + 1)) / 1000.0) * 1000;
    }

    private static UUID category(String suffix) {
        return UUID.fromString("018f0000-0000-7000-8000-" + "0".repeat(12 - suffix.length()) + suffix);
    }
}
