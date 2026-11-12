package io.github.thphuc06.jars.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.UserApiTestSupport;
import io.github.thphuc06.jars.ledger.IncomeAllocation.Split;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService.Result;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** {@code LedgerApi.allocateIncome}: one income, several jars, all in one transaction. */
@RecordApplicationEvents
class LedgerAllocateIncomeTest extends UserApiTestSupport {

    @Autowired
    LedgerApi ledger;

    @Autowired
    ApplicationEvents events;

    private static IncomeAllocation income(String amount, Split... splits) {
        return new IncomeAllocation(Money.vnd(amount), List.of(splits), CATEGORY_SALARY, "Công ty", "Lương", null);
    }

    private static Split split(UUID jar, String amount) {
        return new Split(jar, Money.vnd(amount));
    }

    private Result<AllocationReceipt> allocate(UserId user, IncomeAllocation cmd) {
        return allocate(user, newKey(), cmd);
    }

    private Result<AllocationReceipt> allocate(UserId user, String key, IncomeAllocation cmd) {
        return ledger.allocateIncome(user, key, IdempotencyService.hash("test", cmd.toString()), cmd);
    }

    private int transactionRows(UserId user) {
        return jdbc.sql("SELECT count(*) FROM transactions WHERE user_id = ?")
                .param(user.value())
                .query(Integer.class)
                .single();
    }

    @Test
    void anIncomeIsSplitOverJars_inOneTransactionWithOneEntryPerJar() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID ffa = jarId(user.token(), "FFA");
        UUID play = jarId(user.token(), "PLAY");

        Result<AllocationReceipt> result = allocate(
                user.id(),
                income("10000000", split(nec, "5500000"), split(ffa, "1000000.50"), split(play, "3499999.50")));

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.replayed()).isFalse();
        AllocationReceipt receipt = result.body();
        assertThat(receipt.amount()).isEqualTo(Money.vnd("10000000"));
        assertThat(receipt.credits()).hasSize(3);
        assertThat(balanceOf(nec)).isEqualByComparingTo("5500000");
        assertThat(balanceOf(ffa)).isEqualByComparingTo("1000000.50");
        assertThat(balanceOf(play)).isEqualByComparingTo("3499999.50");
        for (UUID jar : List.of(nec, ffa, play)) {
            assertBalanceMatchesEntries(jar);
        }
        // one INCOME transaction that belongs to no single jar, and exactly one ALLOCATE entry per jar
        assertThat(transactionRows(user.id())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT type || '/' || COALESCE(jar_id::text, 'none') FROM transactions WHERE id = ?")
                        .param(receipt.transactionId())
                        .query(String.class)
                        .single())
                .isEqualTo("INCOME/none");
        assertThat(jdbc.sql("SELECT count(*) FROM jar_entries WHERE transaction_id = ? AND entry_type = 'ALLOCATE'")
                        .param(receipt.transactionId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);
    }

    @Test
    void theTransactionPostedEvent_isPublishedOnceAsAnIncomeWithoutAJar() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");

        allocate(user.id(), income("3000", split(nec, "2000"), split(edu, "1000")));

        List<TransactionPosted> posted = events.stream(TransactionPosted.class)
                .filter(e -> e.userId().equals(user.id()))
                .toList();
        assertThat(posted).hasSize(1);
        assertThat(posted.getFirst().type()).isEqualTo("INCOME");
        assertThat(posted.getFirst().jarId()).isNull();
        assertThat(posted.getFirst().amount()).isEqualTo(Money.vnd("3000"));
    }

    @Test
    void splitsThatDoNotAddUp_areRejectedWith422_andNothingIsPosted() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");

        assertThatThrownBy(() -> allocate(user.id(), income("1000", split(nec, "600"), split(edu, "399.99"))))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.type().status()).isEqualTo(422);
                    assertThat(e.type().slug()).isEqualTo("allocation-sum-mismatch");
                });
        assertThatThrownBy(() -> allocate(user.id(), income("1000", split(nec, "600"), split(edu, "400.01"))))
                .isInstanceOf(DomainException.class);

        assertThat(transactionRows(user.id())).isZero();
        assertThat(balanceOf(nec)).isEqualByComparingTo("0");
    }

    @Test
    void invalidAllocations_areRejectedWith422() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        Money usd = new Money(new BigDecimal("100"), Currency.getInstance("USD"));

        List<IncomeAllocation> bad = List.of(
                income("0", split(nec, "0")),
                income("-100", split(nec, "-100")),
                income("100"), // no splits
                income("100", split(nec, "150"), split(edu, "-50")),
                income("100", split(nec, "50"), split(nec, "50")), // the same jar twice
                income("100", split(nec, "0"), split(edu, "100")),
                new IncomeAllocation(Money.vnd("100"), List.of(new Split(nec, usd)), null, null, null, null),
                new IncomeAllocation(Money.vnd("100"), List.of(split(null, "100")), null, null, null, null),
                new IncomeAllocation(Money.vnd("100"), null, null, null, null, null),
                new IncomeAllocation(
                        Money.vnd("100"),
                        List.of(split(nec, "100")),
                        null,
                        null,
                        null,
                        Instant.now().plus(5, ChronoUnit.DAYS)));
        for (IncomeAllocation cmd : bad) {
            assertThatThrownBy(() -> allocate(user.id(), cmd))
                    .as(cmd.toString())
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            e -> assertThat(e.type().status()).isEqualTo(422));
        }
        assertThat(transactionRows(user.id())).isZero();
    }

    @Test
    void aJarOfSomeoneElse_is404_andTheOwnJarsAreUntouched() {
        TestUser alice = newTestUser();
        TestUser bob = newTestUser();
        UUID aliceNec = jarId(alice.token(), "NEC");
        UUID bobNec = jarId(bob.token(), "NEC");

        assertThatThrownBy(() -> allocate(alice.id(), income("100", split(aliceNec, "40"), split(bobNec, "60"))))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> assertThat(e.type().status()).isEqualTo(404));

        assertThat(balanceOf(aliceNec)).isEqualByComparingTo("0");
        assertThat(balanceOf(bobNec)).isEqualByComparingTo("0");
        assertThat(transactionRows(alice.id())).isZero();
        assertThat(transactionRows(bob.id())).isZero();
    }

    @Test
    void anArchivedJar_cannotReceiveMoney() {
        TestUser user = newTestUser();
        UUID give = jarId(user.token(), "GIVE");
        UUID nec = jarId(user.token(), "NEC");
        assertThat(delete(user.token(), "/api/v1/jars/" + give).status()).isEqualTo(204);

        assertThatThrownBy(() -> allocate(user.id(), income("100", split(nec, "50"), split(give, "50"))))
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.type().slug()).isEqualTo("jar-archived"));
        assertThat(balanceOf(nec)).isEqualByComparingTo("0");
    }

    @Test
    void theCategoryMustBeAnIncomeCategoryThatExists() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        List<Split> all = List.of(split(nec, "100"));

        IncomeAllocation expenseCategory = new IncomeAllocation(Money.vnd("100"), all, CATEGORY_FOOD, null, null, null);
        IncomeAllocation unknownCategory =
                new IncomeAllocation(Money.vnd("100"), all, UUID.randomUUID(), null, null, null);
        IncomeAllocation noCategory = new IncomeAllocation(Money.vnd("100"), all, null, null, null, null);

        assertThatThrownBy(() -> allocate(user.id(), expenseCategory))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> assertThat(e.type().status()).isEqualTo(422));
        assertThatThrownBy(() -> allocate(user.id(), unknownCategory))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> assertThat(e.type().status()).isEqualTo(404));
        assertThat(allocate(user.id(), noCategory).status()).isEqualTo(201);
    }

    @Test
    void sameKeyTwice_allocatesOnceAndReplaysTheFirstReceipt() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        IncomeAllocation cmd = income("1000", split(nec, "700"), split(edu, "300"));
        String key = newKey();

        Result<AllocationReceipt> first = allocate(user.id(), key, cmd);
        Result<AllocationReceipt> second = allocate(user.id(), key, cmd);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.body().transactionId()).isEqualTo(first.body().transactionId());
        assertThat(balanceOf(nec)).as("credited once").isEqualByComparingTo("700");
        assertThat(balanceOf(edu)).isEqualByComparingTo("300");
        assertThat(transactionRows(user.id())).isEqualTo(1);
    }

    @Test
    void sameKeyForADifferentRequest_is422() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        String key = newKey();
        allocate(user.id(), key, income("1000", split(nec, "1000")));

        assertThatThrownBy(() -> allocate(user.id(), key, income("2000", split(nec, "2000"))))
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.type().slug()).isEqualTo("idempotency-key-reused"));
        assertThat(balanceOf(nec)).isEqualByComparingTo("1000");
    }

    @Test
    void aFailedAllocation_doesNotConsumeItsKey() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        String key = newKey();

        assertThatThrownBy(() -> allocate(user.id(), key, income("1000", split(nec, "999"))))
                .isInstanceOf(DomainException.class);
        Result<AllocationReceipt> fixed = allocate(user.id(), key, income("1000", split(nec, "1000")));

        assertThat(fixed.replayed()).isFalse();
        assertThat(balanceOf(nec)).isEqualByComparingTo("1000");
    }

    @Test
    void concurrentAllocationsOverTheSameJars_neverLoseAnUpdate() throws Exception {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        UUID play = jarId(user.token(), "PLAY");
        int writers = 8;

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        allocate(user.id(), income("600", split(nec, "300"), split(edu, "200"), split(play, "100")));
                        return true;
                    } catch (ConcurrencyFailureException e) {
                        return false; // lost the race five times: the API would answer 409
                    }
                }));
            }
            start.countDown();
            long accepted = 0;
            for (Future<Boolean> f : futures) {
                if (f.get()) {
                    accepted++;
                }
            }

            assertThat(accepted).as("optimistic retries let writers through").isGreaterThanOrEqualTo(writers / 2);
            assertThat(balanceOf(nec)).isEqualByComparingTo(BigDecimal.valueOf(300 * accepted));
            assertThat(balanceOf(edu)).isEqualByComparingTo(BigDecimal.valueOf(200 * accepted));
            assertThat(balanceOf(play)).isEqualByComparingTo(BigDecimal.valueOf(100 * accepted));
            assertThat(transactionRows(user.id())).isEqualTo((int) accepted);
            for (UUID jar : List.of(nec, edu, play)) {
                assertBalanceMatchesEntries(jar);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
