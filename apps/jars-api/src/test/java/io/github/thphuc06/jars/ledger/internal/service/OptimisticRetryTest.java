package io.github.thphuc06.jars.ledger.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;

class OptimisticRetryTest {

    private final List<Long> sleeps = new ArrayList<>();
    private final OptimisticRetry retry = new OptimisticRetry(3, sleeps::add);

    @Test
    void succeedsImmediately_withoutSleeping() {
        assertThat(retry.run(() -> "ok")).isEqualTo("ok");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesAfterAConflictAndReturnsTheLaterResult() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.run(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new OptimisticLockingFailureException("lost the race");
            }
            return "third time lucky";
        });

        assertThat(result).isEqualTo("third time lucky");
        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasSize(2); // slept between the attempts, not after the last one
    }

    @Test
    void aDeadlockVictim_isRetriedLikeAnOptimisticConflict() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.run(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new CannotAcquireLockException("deadlock detected");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void givesUpAfterTheMaximumNumberOfAttemptsAndRethrows() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(() -> {
                    calls.incrementAndGet();
                    throw new OptimisticLockingFailureException("always conflicts");
                }))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(calls).hasValue(3);
    }

    @Test
    void otherExceptions_arePropagatedAtOnce() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(() -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("not a conflict");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    void backoffGrowsWithTheAttemptNumber() {
        assertThatThrownBy(() -> retry.run(() -> {
                    throw new OptimisticLockingFailureException("x");
                }))
                .isInstanceOf(OptimisticLockingFailureException.class);

        // attempt 1 sleeps 2..11 ms, attempt 2 sleeps 4..22 ms
        assertThat(sleeps.get(0)).isBetween(2L, 11L);
        assertThat(sleeps.get(1)).isBetween(4L, 22L);
    }

    @Test
    void atLeastOneAttemptIsRequired() {
        assertThatThrownBy(() -> new OptimisticRetry(0, ms -> {})).isInstanceOf(IllegalArgumentException.class);
    }
}
