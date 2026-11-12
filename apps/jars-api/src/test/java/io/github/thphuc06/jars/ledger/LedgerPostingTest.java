package io.github.thphuc06.jars.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.ApiTestSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import tools.jackson.databind.JsonNode;

/** Posting money: the rules, the ledger invariant, idempotency, and what happens under concurrent writes. */
class LedgerPostingTest extends ApiTestSupport {

    @Test
    void newUser_getsTheSixDefaultJarsWhoseSharesAddUpToOneHundred() {
        JsonNode jars = get(newUser(), "/api/v1/jars").json();

        assertThat(jars).hasSize(6);
        List<String> codes = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode jar : jars) {
            codes.add(jar.get("code").asString());
            total = total.add(jar.get("targetPct").decimalValue());
            assertThat(jar.get("balance").get("amount").asString()).isEqualTo("0.00");
            assertThat(jar.get("balance").get("currency").asString()).isEqualTo("VND");
        }
        assertThat(codes).containsExactly("NEC", "FFA", "LTSS", "EDU", "PLAY", "GIVE");
        assertThat(total).isEqualByComparingTo("100");
    }

    @Test
    void expense_reducesTheJarAndKeepsTheLedgerConsistent() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        assertThat(post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "1000000"))
                        .status())
                .isEqualTo(201);

        Resp spent = spend(user, nec, "85000");

        assertThat(spent.status()).isEqualTo(201);
        JsonNode receipt = spent.json();
        assertThat(receipt.get("transaction").get("type").asString()).isEqualTo("EXPENSE");
        assertThat(receipt.get("transaction").get("amount").get("amount").asString())
                .isEqualTo("85000.00");
        assertThat(receipt.get("jarBalances")
                        .get(0)
                        .get("balance")
                        .get("amount")
                        .asString())
                .isEqualTo("915000.00");
        assertThat(balanceOf(nec)).isEqualByComparingTo("915000");
        assertBalanceMatchesEntries(nec);
        assertThat(spent.header("Idempotency-Replayed")).isEqualTo("false");
    }

    @Test
    void incomeIntoAJar_creditsIt_andIncomeWithoutAJar_movesNoJarMoney() {
        JwtRequestPostProcessor user = newUser();
        UUID play = jarId(user, "PLAY");

        post(user, "/api/v1/transactions", newKey(), incomeBody(play, "500000"));
        Resp unassigned = post(user, "/api/v1/transactions", newKey(), incomeBody(null, "700000"));

        assertThat(unassigned.status()).isEqualTo(201);
        assertThat(unassigned.json().get("jarBalances")).isEmpty();
        assertThat(balanceOf(play)).isEqualByComparingTo("500000");
        assertBalanceMatchesEntries(play);
    }

    @Test
    void anExpenseMayOverdrawAJar_theNegativeBalanceShowsTheOverspend() {
        JwtRequestPostProcessor user = newUser();
        UUID play = jarId(user, "PLAY");

        Resp spent = spend(user, play, "50000");

        assertThat(spent.status()).isEqualTo(201);
        assertThat(balanceOf(play)).isEqualByComparingTo("-50000");
        assertBalanceMatchesEntries(play);
    }

    @Test
    void invalidPostings_areRejectedWithTheRightStatus() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");

        assertThat(post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "-5", CATEGORY_FOOD, null))
                        .status())
                .as("negative amount")
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "0", CATEGORY_FOOD, null))
                        .status())
                .as("zero amount")
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "1.239", CATEGORY_FOOD, null))
                        .status())
                .as("three decimals")
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/transactions", newKey(), "{\"type\":\"EXPENSE\",\"amount\":100}")
                        .status())
                .as("expense without a jar")
                .isEqualTo(422);
        assertThat(post(
                                user,
                                "/api/v1/transactions",
                                newKey(),
                                "{\"type\":\"JAR_TRANSFER\",\"amount\":100,\"jarId\":\"" + nec + "\"}")
                        .status())
                .as("transfer through the wrong endpoint")
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/transactions", newKey(), "{\"type\":\"NOPE\",\"amount\":100}")
                        .status())
                .as("unknown type")
                .isEqualTo(400);
        assertThat(post(
                                user,
                                "/api/v1/transactions",
                                newKey(),
                                expenseBody(UUID.randomUUID(), "100", CATEGORY_FOOD, null))
                        .status())
                .as("unknown jar")
                .isEqualTo(404);
        assertThat(post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "100", CATEGORY_SALARY, null))
                        .status())
                .as("income category on an expense")
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "100", UUID.randomUUID(), null))
                        .status())
                .as("unknown category")
                .isEqualTo(404);
        assertThat(post(
                                user,
                                "/api/v1/transactions",
                                newKey(),
                                expenseBody(nec, "100", CATEGORY_FOOD, "2999-01-01T00:00:00Z"))
                        .status())
                .as("far in the future")
                .isEqualTo(422);
        assertThat(balanceOf(nec)).as("nothing was posted").isEqualByComparingTo("0");
    }

    @Test
    void sameIdempotencyKeyTwice_postsOnceAndReplaysTheFirstResponse() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        String key = newKey();
        String body = expenseBody(nec, "120000", CATEGORY_FOOD, null);

        Resp first = post(user, "/api/v1/transactions", key, body);
        Resp second = post(user, "/api/v1/transactions", key, body);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(second.json().get("transaction").get("id"))
                .isEqualTo(first.json().get("transaction").get("id"));
        assertThat(balanceOf(nec)).isEqualByComparingTo("-120000"); // debited once
        assertThat(entriesCount(nec)).isEqualTo(1);
    }

    @Test
    void theSameAmountWrittenDifferently_isTheSameRequest() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        String key = newKey();

        post(user, "/api/v1/transactions", key, expenseBody(nec, "120000", CATEGORY_FOOD, null));
        Resp again = post(user, "/api/v1/transactions", key, expenseBody(nec, "120000.00", CATEGORY_FOOD, null));

        assertThat(again.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(entriesCount(nec)).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequest_is422() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        String key = newKey();
        post(user, "/api/v1/transactions", key, expenseBody(nec, "100000", CATEGORY_FOOD, null));

        Resp other = post(user, "/api/v1/transactions", key, expenseBody(nec, "999999", CATEGORY_FOOD, null));

        assertThat(other.status()).isEqualTo(422);
        assertThat(other.json().get("type").asString()).endsWith("idempotency-key-reused");
        assertThat(balanceOf(nec)).isEqualByComparingTo("-100000");
    }

    @Test
    void missingOrMalformedIdempotencyKey_is400AndNothingIsPosted() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        String body = expenseBody(nec, "100", CATEGORY_FOOD, null);

        assertThat(post(user, "/api/v1/transactions", null, body).status()).isEqualTo(400);
        assertThat(post(user, "/api/v1/transactions", "short", body).status()).isEqualTo(400);
        assertThat(balanceOf(nec)).isEqualByComparingTo("0");
    }

    @Test
    void aFailedPosting_doesNotConsumeItsKey_soTheClientCanFixAndRetry() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        String key = newKey();

        Resp bad = post(
                user,
                "/api/v1/transactions",
                key,
                expenseBody(nec, "100", CATEGORY_SALARY, null)); // wrong category kind
        Resp fixed = post(user, "/api/v1/transactions", key, expenseBody(nec, "100", CATEGORY_FOOD, null));

        assertThat(bad.status()).isEqualTo(422);
        assertThat(fixed.status()).isEqualTo(201);
        assertThat(fixed.header("Idempotency-Replayed")).isEqualTo("false");
    }

    @Test
    void concurrentRequestsWithTheSameKey_postExactlyOnce() throws Exception {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        String key = newKey();
        String body = expenseBody(nec, "10000", CATEGORY_FOOD, null);

        List<Resp> responses = runConcurrently(8, i -> post(user, "/api/v1/transactions", key, body));

        assertThat(responses).extracting(Resp::status).containsOnly(201);
        assertThat(responses)
                .extracting(r -> r.json().get("transaction").get("id").asString())
                .containsOnly(
                        responses.getFirst().json().get("transaction").get("id").asString());
        assertThat(responses.stream()
                        .filter(r -> "false".equals(r.header("Idempotency-Replayed")))
                        .count())
                .isEqualTo(1);
        assertThat(balanceOf(nec)).isEqualByComparingTo("-10000");
        assertThat(entriesCount(nec)).isEqualTo(1);
    }

    @Test
    void concurrentSpendingFromOneJar_neverLosesAnUpdate() throws Exception {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        int writers = 12;

        // different keys: twelve independent expenses of 1,000 fighting over the same jar row
        List<Resp> responses = runConcurrently(writers, i -> spend(user, nec, "1000"));

        long created = responses.stream().filter(r -> r.status() == 201).count();
        long conflicts = responses.stream().filter(r -> r.status() == 409).count();
        assertThat(created + conflicts)
                .as("only success or a clean conflict, nothing else")
                .isEqualTo(writers);
        assertThat(created).as("optimistic retries let most writers through").isGreaterThanOrEqualTo(writers / 2);
        // the point of the exercise: every accepted expense is in the balance, every rejected one is not
        assertThat(balanceOf(nec)).isEqualByComparingTo(BigDecimal.valueOf(-1000L * created));
        assertThat(entriesCount(nec)).isEqualTo((int) created);
        assertBalanceMatchesEntries(nec);
    }

    @Test
    void transfer_movesMoneyBetweenJarsInOneStep() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID edu = jarId(user, "EDU");
        post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "1000000"));

        Resp moved = post(user, "/api/v1/jars/transfers", newKey(), transferBody(nec, edu, "300000"));

        assertThat(moved.status()).isEqualTo(201);
        assertThat(moved.json().get("transaction").get("type").asString()).isEqualTo("JAR_TRANSFER");
        assertThat(balanceOf(nec)).isEqualByComparingTo("700000");
        assertThat(balanceOf(edu)).isEqualByComparingTo("300000");
        assertBalanceMatchesEntries(nec);
        assertBalanceMatchesEntries(edu);
    }

    @Test
    void transfer_withoutEnoughMoney_is422AndChangesNothing() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID edu = jarId(user, "EDU");
        post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "100000"));

        Resp moved = post(user, "/api/v1/jars/transfers", newKey(), transferBody(nec, edu, "100001"));

        assertThat(moved.status()).isEqualTo(422);
        assertThat(moved.json().get("type").asString()).endsWith("insufficient-jar-balance");
        assertThat(balanceOf(nec)).isEqualByComparingTo("100000");
        assertThat(balanceOf(edu)).isEqualByComparingTo("0");
    }

    @Test
    void transfer_betweenTheSameJar_orTwiceWithTheSameKey_isHandled() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID edu = jarId(user, "EDU");
        post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "1000"));
        String key = newKey();

        assertThat(post(user, "/api/v1/jars/transfers", newKey(), transferBody(nec, nec, "10"))
                        .status())
                .isEqualTo(422);
        post(user, "/api/v1/jars/transfers", key, transferBody(nec, edu, "400"));
        Resp replay = post(user, "/api/v1/jars/transfers", key, transferBody(nec, edu, "400"));

        assertThat(replay.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(balanceOf(edu)).isEqualByComparingTo("400"); // moved once
    }

    @Test
    void concurrentTransfersBetweenTwoJars_keepBothLedgersConsistent() throws Exception {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID edu = jarId(user, "EDU");
        post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "100000"));
        post(user, "/api/v1/transactions", newKey(), incomeBody(edu, "100000"));

        // half the writers move NEC -> EDU, the other half EDU -> NEC, so the two rows are locked in opposite order
        List<Resp> responses = runConcurrently(
                10,
                i -> i % 2 == 0
                        ? post(user, "/api/v1/jars/transfers", newKey(), transferBody(nec, edu, "1000"))
                        : post(user, "/api/v1/jars/transfers", newKey(), transferBody(edu, nec, "1000")));

        // isSubsetOf, not containsOnly: containsOnly also demands that every listed value shows up, and a run where
        // no writer loses the retry race (all 201) is a perfectly good outcome
        assertThat(responses).extracting(Resp::status).isSubsetOf(201, 409);
        assertBalanceMatchesEntries(nec);
        assertBalanceMatchesEntries(edu);
        assertThat(balanceOf(nec).add(balanceOf(edu)))
                .as("money is neither created nor destroyed")
                .isEqualByComparingTo("200000");
    }

    // ------------------------------------------------------------------ helpers

    private int entriesCount(UUID jarId) {
        return jdbc.sql("SELECT count(*) FROM jar_entries WHERE jar_id = ?")
                .param(jarId)
                .query(Integer.class)
                .single();
    }

    private interface Task {
        Resp run(int index);
    }

    /** Releases {@code count} tasks at the same instant and collects their responses. */
    private List<Resp> runConcurrently(int count, Task task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Resp>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.run(index);
                }));
            }
            start.countDown();
            List<Resp> results = new ArrayList<>();
            for (Future<Resp> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
