package io.github.thphuc06.jars.insights.internal.service;

import io.github.thphuc06.jars.ledger.JarCreated;
import io.github.thphuc06.jars.ledger.TransactionPosted;
import io.github.thphuc06.jars.ledger.TransactionRecategorized;
import io.github.thphuc06.jars.platform.idempotency.ProcessedMessages;
import java.time.LocalDate;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The insights module's reaction to ledger events (flow F3). Each listener runs after the ledger's transaction has
 * committed, in a transaction of its own, and stays in the event publication registry until it finishes, so a crash
 * in between does not lose the event. Delivery is at-least-once, hence the {@link ProcessedMessages} check first.
 */
@Component
class InsightsEventHandlers {

    private static final Logger log = LoggerFactory.getLogger(InsightsEventHandlers.class);

    static final String POSTED = "insights.transaction-posted";
    static final String RECATEGORIZED = "insights.transaction-recategorized";

    private final ProcessedMessages inbox;
    private final ReadModelWriter readModel;
    private final DetectionService detection;

    InsightsEventHandlers(ProcessedMessages inbox, ReadModelWriter readModel, DetectionService detection) {
        this.inbox = inbox;
        this.readModel = readModel;
        this.detection = detection;
    }

    @ApplicationModuleListener
    void on(TransactionPosted event) {
        if (!inbox.firstTime(POSTED, event.eventId().toString())) {
            log.debug("Skipping duplicate {}", event.eventId());
            return;
        }
        LocalDate month = Months.startOf(event.occurredAt());
        switch (event.type()) {
            case "EXPENSE" -> {
                readModel.addExpense(event.userId(), event.jarId(), event.categoryId(), month, event.amount());
                detection.observeExpense(
                        event.userId(), event.jarId(), event.transactionId(), event.amount(), event.occurredAt());
            }
            case "INCOME" -> readModel.addIncome(event.userId(), month, event.amount());
            default -> {
                // JAR_TRANSFER moves money between jars; it is neither income nor spending
            }
        }
    }

    @ApplicationModuleListener
    void on(TransactionRecategorized event) {
        if (!inbox.firstTime(RECATEGORIZED, event.eventId().toString())) {
            return;
        }
        if (!"EXPENSE".equals(event.type())) {
            return; // only expenses are broken down by jar and category
        }
        LocalDate month = Months.startOf(event.occurredAt());
        if (!Objects.equals(event.oldCategoryId(), event.newCategoryId())) {
            readModel.moveBetweenCategories(
                    event.userId(), event.oldCategoryId(), event.newCategoryId(), month, event.amount());
        }
        if (!Objects.equals(event.oldJarId(), event.newJarId())) {
            readModel.moveBetweenJars(event.userId(), event.oldJarId(), event.newJarId(), month, event.amount());
        }
    }

    @ApplicationModuleListener
    void on(JarCreated event) {
        // idempotent by itself (insert ... on conflict do nothing), so it needs no inbox entry
        detection.ensureState(event.userId(), event.jarId());
    }
}
