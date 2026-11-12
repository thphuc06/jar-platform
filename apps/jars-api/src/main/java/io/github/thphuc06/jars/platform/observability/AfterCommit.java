package io.github.thphuc06.jars.platform.observability;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs an action once the surrounding transaction has committed, or at once when there is no transaction.
 *
 * <p>Business counters use it so that they count what really happened: a posting that is retried after an optimistic
 * lock conflict, or rolled back, must not be counted. A {@code @TransactionalEventListener} would do the same, but
 * Spring Modulith records a publication row for every transactional listener, which is an outbox write per event
 * only to increment a counter.
 */
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
