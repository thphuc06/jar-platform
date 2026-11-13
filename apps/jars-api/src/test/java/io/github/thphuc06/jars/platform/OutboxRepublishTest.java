package io.github.thphuc06.jars.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.thphuc06.jars.ApiTestSupport;
import io.github.thphuc06.jars.ledger.TransactionPosted;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ApplicationEventMulticaster;

/**
 * The outbox is only worth anything if a row that is still in {@code event_publication} when the application starts
 * is delivered again (the dev stack and ECS tasks stop between a commit and its listener). Modulith does that in
 * {@code afterSingletonsInstantiated} when {@code spring.modulith.events.republish-outstanding-events-on-restart} is
 * on, so the test leaves rows behind exactly as a stopped task would and runs that startup step again.
 */
class OutboxRepublishTest extends ApiTestSupport {

    private static final String LISTENER = "io.github.thphuc06.jars.insights.internal.service.InsightsEventHandlers"
            + ".on(io.github.thphuc06.jars.ledger.TransactionPosted)";

    @Autowired
    @Qualifier("applicationEventMulticaster")
    ApplicationEventMulticaster multicaster;

    @Test
    void anIncompletePublicationAtStartup_isDeliveredAgain_andItsEffectAppearsOnce() {
        UUID jar = UUID.randomUUID();
        TransactionPosted event = new TransactionPosted(
                Uuids.v7(),
                UUID.randomUUID(),
                new UserId(UUID.randomUUID()),
                "EXPENSE",
                Money.vnd("1000"),
                jar,
                null,
                Instant.now(),
                "MANUAL");
        // one row the listener never saw, and one for the same event whose listener had finished but whose row was
        // not yet deleted when the task stopped: the second delivery must be a no-op
        UUID first = leaveIncompletePublication(event);
        UUID repeat = leaveIncompletePublication(event);

        ((SmartInitializingSingleton) multicaster).afterSingletonsInstantiated();

        await().atMost(Duration.ofSeconds(15)).until(() -> !stillIncomplete(first) && !stillIncomplete(repeat));
        assertThat(txnCount(jar))
                .as("counted once although it was delivered twice")
                .isEqualTo(1);
    }

    private UUID leaveIncompletePublication(TransactionPosted event) {
        UUID id = Uuids.v7();
        jdbc.sql("""
                        INSERT INTO event_publication
                            (id, listener_id, event_type, serialized_event, publication_date, status, completion_attempts)
                        VALUES (?, ?, ?, ?, now(), 'PUBLISHED', 0)
                        """)
                .params(id, LISTENER, TransactionPosted.class.getName(), mapper.writeValueAsString(event))
                .update();
        return id;
    }

    private boolean stillIncomplete(UUID id) {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE id = ?")
                        .param(id)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    private int txnCount(UUID jar) {
        return jdbc.sql("SELECT COALESCE(SUM(txn_count), 0) FROM monthly_jar_spend WHERE jar_id = ?")
                .param(jar)
                .query(Integer.class)
                .single();
    }
}
