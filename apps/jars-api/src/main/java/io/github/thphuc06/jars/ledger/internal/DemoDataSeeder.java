package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.shared.BusinessTime;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local demo data (profile "local" only, and only when asked):
 *
 * <pre>./mvnw spring-boot:run -Dspring-boot.run.arguments=--jars.seed.email=you@example.com</pre>
 *
 * The user must have signed in once (the user row is created by the first request). Every step goes through
 * PostingService, i.e. the same validation, ledger entries, events and idempotency as a real request. Each step's
 * Idempotency-Key is derived from the user and the step number, so two instances started at once cannot record a
 * step twice. A user who already has transactions is left alone.
 */
@Component
@Profile("local")
@ConditionalOnProperty("jars.seed.email")
class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final IdentityApi identity;
    private final LedgerQueries queries;
    private final PostingService posting;
    private final LedgerTransactionRepository transactions;
    private final Clock clock;
    private final String email;

    DemoDataSeeder(
            IdentityApi identity,
            LedgerQueries queries,
            PostingService posting,
            LedgerTransactionRepository transactions,
            Clock clock,
            @Value("${jars.seed.email}") String email) {
        this.identity = identity;
        this.queries = queries;
        this.posting = posting;
        this.transactions = transactions;
        this.clock = clock;
        this.email = email;
    }

    @Override
    public void run(ApplicationArguments args) {
        Optional<UUID> user = identity.findUserIdByEmail(email);
        if (user.isEmpty()) {
            log.warn("demo data: no user with that e-mail yet; sign in once in the web app, then restart");
            return;
        }
        seed(user.get());
    }

    /** @return the number of steps recorded (0 when the user already had transactions) */
    int seed(UUID userId) {
        if (transactions.existsByUserId(userId)) {
            log.info("demo data: user already has transactions, nothing seeded");
            return 0;
        }
        LedgerQueries.JarsOverview overview = queries.jarsOf(userId); // also creates the jars if needed
        Map<JarCode, UUID> jarIds = new EnumMap<>(JarCode.class);
        overview.jars().forEach(jar -> jarIds.put(jar.code(), jar.id()));
        jarIds.put(JarCode.POOL, overview.poolJarId());

        List<DemoDataPlan.Step> steps = DemoDataPlan.plan(userId, BusinessTime.today(clock));
        for (int i = 0; i < steps.size(); i++) {
            DemoDataPlan.Step step = steps.get(i);
            UUID key = UUID.nameUUIDFromBytes(("demo-data:" + userId + ":" + i).getBytes(StandardCharsets.UTF_8));
            switch (step.kind()) {
                case INCOME ->
                    posting.post(
                            userId,
                            key,
                            new PostingService.PostTransaction(
                                    TransactionType.INCOME,
                                    step.amount(),
                                    null,
                                    step.categoryId(),
                                    step.counterparty(),
                                    null,
                                    step.occurredAt()));
                case EXPENSE ->
                    posting.post(
                            userId,
                            key,
                            new PostingService.PostTransaction(
                                    TransactionType.EXPENSE,
                                    step.amount(),
                                    jarIds.get(step.jar()),
                                    step.categoryId(),
                                    step.counterparty(),
                                    null,
                                    step.occurredAt()));
                case ALLOCATE ->
                    posting.transfer(
                            userId,
                            key,
                            new PostingService.Transfer(
                                    overview.poolJarId(),
                                    jarIds.get(step.jar()),
                                    step.amount(),
                                    "Allocate salary",
                                    step.occurredAt()));
            }
        }
        log.info("demo data: {} steps recorded", steps.size());
        return steps.size();
    }
}
