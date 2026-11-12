package io.github.thphuc06.jars.ledger.internal.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;

/**
 * Runs a unit of work again when it loses a concurrency race: an optimistic-lock version conflict, or being picked as\n * the victim of a database deadlock or serialization failure (both leave the transaction rolled back, so a retry is\n * safe). The work must start its own transaction on every
 * call (a {@code TransactionTemplate} inside the supplier): a transaction that hit a version conflict is rollback-only
 * and its entities are stale, so retrying <em>inside</em> it can never succeed. That is why this sits outside.
 */
final class OptimisticRetry {

    interface Sleeper {
        void sleep(long millis);
    }

    private final int maxAttempts;
    private final Sleeper sleeper;

    OptimisticRetry(int maxAttempts, Sleeper sleeper) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.sleeper = sleeper;
    }

    static OptimisticRetry withJitter(int maxAttempts) {
        return new OptimisticRetry(maxAttempts, millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    <T> T run(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (ConcurrencyFailureException e) {
                if (attempt >= maxAttempts) {
                    throw e; // surfaces as 409 version-conflict
                }
                // random backoff that grows with the attempt, so colliding requests spread out instead of colliding
                // again
                sleeper.sleep(ThreadLocalRandom.current().nextLong(2, 12) * attempt);
            }
        }
    }
}
