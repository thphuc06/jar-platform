package io.github.thphuc06.jars.budgeting.internal.service;

import io.github.thphuc06.jars.budgeting.internal.persistence.JarPeriodStore;
import io.github.thphuc06.jars.ledger.TransactionPosted;
import io.github.thphuc06.jars.ledger.TransactionRecategorized;
import io.github.thphuc06.jars.platform.idempotency.ProcessedMessages;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code jar_periods.spent_amount} up to date from ledger events. Each listener runs after the ledger's
 * transaction has committed, in a transaction of its own, and stays in the event publication registry until it
 * finishes. Delivery is at-least-once, hence the {@link ProcessedMessages} check first; every change is an additive
 * upsert, so applying events in any order gives the same totals.
 */
@Component
class BudgetEventHandlers {

    private static final Logger log = LoggerFactory.getLogger(BudgetEventHandlers.class);

    static final String POSTED = "budgeting.transaction-posted";
    static final String RECATEGORIZED = "budgeting.transaction-recategorized";

    private final ProcessedMessages inbox;
    private final JarPeriodStore periods;
    private final Clock clock;

    BudgetEventHandlers(ProcessedMessages inbox, JarPeriodStore periods, Clock clock) {
        this.inbox = inbox;
        this.periods = periods;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(TransactionPosted event) {
        if (!inbox.firstTime(POSTED, event.eventId().toString())) {
            log.debug("Skipping duplicate {}", event.eventId());
            return;
        }
        // only expenses count as spending; income, allocations and jar transfers are not spent
        if ("EXPENSE".equals(event.type()) && event.jarId() != null) {
            periods.addSpent(
                    event.userId(),
                    event.jarId(),
                    month(event.occurredAt()),
                    event.amount().amount(),
                    now());
        }
    }

    @ApplicationModuleListener
    void on(TransactionRecategorized event) {
        if (!inbox.firstTime(RECATEGORIZED, event.eventId().toString())) {
            return;
        }
        if (!"EXPENSE".equals(event.type()) || Objects.equals(event.oldJarId(), event.newJarId())) {
            return; // only an expense that changed jar moves spending between jars
        }
        LocalDate month = month(event.occurredAt());
        // touch the two rows in a fixed order, so two moves in opposite directions cannot deadlock
        moveInOrder(event.userId(), event.oldJarId(), event.newJarId(), month, event);
    }

    private void moveInOrder(UserId user, UUID from, UUID to, LocalDate month, TransactionRecategorized event) {
        java.math.BigDecimal amount = event.amount().amount();
        Instant now = now();
        if (from.compareTo(to) < 0) {
            periods.addSpent(user, from, month, amount.negate(), now);
            periods.addSpent(user, to, month, amount, now);
        } else {
            periods.addSpent(user, to, month, amount, now);
            periods.addSpent(user, from, month, amount.negate(), now);
        }
    }

    private static LocalDate month(Instant occurredAt) {
        return Months.startOf(occurredAt);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
