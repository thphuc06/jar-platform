package io.github.thphuc06.jars.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.ApiTestSupport;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import tools.jackson.databind.JsonNode;

/** Reading the ledger (pagination, balances over time), jar management, and keeping users apart. */
class LedgerReadAndSecurityTest extends ApiTestSupport {

    // ------------------------------------------------------------ categories

    @Test
    void categories_listSystemOnesForEveryone() {
        JsonNode categories = get(newUser(), "/api/v1/categories").json();

        Set<String> names = new HashSet<>();
        categories.forEach(c -> names.add(c.get("name").asString()));
        assertThat(names).contains("Ăn uống", "Di chuyển", "Lương");
        assertThat(categories)
                .allSatisfy(c -> assertThat(c.get("system").asBoolean()).isTrue());
    }

    // ------------------------------------------------------------ pagination

    @Test
    void moneyInbox_pagesThroughEveryRowExactlyOnce_evenWhenTimestampsTie() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        // 25 expenses on 5 distinct instants, five per instant, so ties must be broken by id
        for (int i = 0; i < 25; i++) {
            String when = "2026-10-1" + (i % 5) + "T03:00:00Z";
            assertThat(post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "1000", CATEGORY_FOOD, when))
                            .status())
                    .isEqualTo(201);
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = get(user, "/api/v1/transactions?limit=10" + (cursor == null ? "" : "&cursor=" + cursor))
                    .json();
            page.get("items").forEach(t -> seen.add(t.get("id").asString()));
            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(25).doesNotHaveDuplicates();
    }

    @Test
    void moneyInbox_isNewestFirst_andJoinsJarAndCategoryNames() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "1000", CATEGORY_FOOD, "2026-10-01T03:00:00Z"));
        post(
                user,
                "/api/v1/transactions",
                newKey(),
                expenseBody(nec, "2000", CATEGORY_TRANSPORT, "2026-10-02T03:00:00Z"));

        JsonNode items = get(user, "/api/v1/transactions").json().get("items");

        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("occurredAt").asString()).startsWith("2026-10-02");
        assertThat(items.get(0).get("categoryName").asString()).isEqualTo("Di chuyển");
        assertThat(items.get(0).get("jarName").asString()).isEqualTo("Thiết yếu");
        assertThat(items.get(1).get("amount").get("amount").asString()).isEqualTo("1000.00");
    }

    @Test
    void moneyInbox_filtersByTypeJarCategoryAndDateRange() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID play = jarId(user, "PLAY");
        post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "5000000"));
        post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "1000", CATEGORY_FOOD, "2026-09-10T03:00:00Z"));
        post(
                user,
                "/api/v1/transactions",
                newKey(),
                expenseBody(play, "2000", CATEGORY_TRANSPORT, "2026-10-10T03:00:00Z"));
        post(user, "/api/v1/transactions", newKey(), expenseBody(play, "3000", CATEGORY_FOOD, "2026-10-20T03:00:00Z"));

        assertThat(count(user, "type=INCOME")).isEqualTo(1);
        assertThat(count(user, "type=EXPENSE")).isEqualTo(3);
        assertThat(count(user, "jarId=" + play)).isEqualTo(2);
        assertThat(count(user, "categoryId=" + CATEGORY_FOOD)).isEqualTo(2);
        assertThat(count(user, "type=EXPENSE&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z"))
                .isEqualTo(2);
        assertThat(count(user, "type=EXPENSE&from=2026-10-15T00:00:00Z")).isEqualTo(1);
    }

    @Test
    void moneyInbox_badLimitOrCursor_is400() {
        JwtRequestPostProcessor user = newUser();

        assertThat(get(user, "/api/v1/transactions?limit=0").status()).isEqualTo(400);
        assertThat(get(user, "/api/v1/transactions?limit=101").status()).isEqualTo(400);
        assertThat(get(user, "/api/v1/transactions?cursor=garbage").status()).isEqualTo(400);
    }

    // ------------------------------------------------------- daily balances

    @Test
    void dailyBalances_accumulateFromTheOpeningBalanceAndRepeatQuietDays() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        post(user, "/api/v1/transactions", newKey(), backdated("INCOME", nec, "1000000", today.minusDays(10)));
        post(user, "/api/v1/transactions", newKey(), backdated("EXPENSE", nec, "100000", today.minusDays(2)));

        JsonNode days = get(user, "/api/v1/balances/daily?days=4").json();

        assertThat(days).hasSize(4);
        assertThat(days.get(0).get("date").asString())
                .isEqualTo(today.minusDays(3).toString());
        assertThat(days.get(0).get("balance").get("amount").asString())
                .isEqualTo("1000000.00"); // opening balance, income was before the window
        assertThat(days.get(1).get("balance").get("amount").asString()).isEqualTo("900000.00"); // the expense day
        assertThat(days.get(2).get("balance").get("amount").asString()).isEqualTo("900000.00"); // quiet day repeats
        assertThat(days.get(3).get("date").asString()).isEqualTo(today.toString());
    }

    @Test
    void dailyBalances_ofAUserWithNoEntries_areZero_andTheRangeIsBounded() {
        JwtRequestPostProcessor user = newUser();

        JsonNode days = get(user, "/api/v1/balances/daily?days=3").json();

        assertThat(days).hasSize(3);
        assertThat(days)
                .allSatisfy(d ->
                        assertThat(d.get("balance").get("amount").asString()).isEqualTo("0.00"));
        assertThat(get(user, "/api/v1/balances/daily?days=0").status()).isEqualTo(400);
        assertThat(get(user, "/api/v1/balances/daily?days=400").status()).isEqualTo(400);
    }

    // -------------------------------------------------------------- recategorize

    @Test
    void recategorize_changesTheCategoryOnly() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        String txId =
                spend(user, nec, "1000").json().get("transaction").get("id").asString();

        Resp changed = patch(user, "/api/v1/transactions/" + txId, "{\"categoryId\":\"" + CATEGORY_TRANSPORT + "\"}");

        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.json().get("categoryId").asString()).isEqualTo(CATEGORY_TRANSPORT.toString());
        assertThat(balanceOf(nec)).isEqualByComparingTo("-1000"); // money untouched
    }

    @Test
    void recategorize_toAnotherJar_givesTheMoneyBackAndTakesItFromTheNewJar() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID play = jarId(user, "PLAY");
        String txId =
                spend(user, nec, "40000").json().get("transaction").get("id").asString();

        Resp moved = patch(user, "/api/v1/transactions/" + txId, "{\"jarId\":\"" + play + "\"}");

        assertThat(moved.status()).isEqualTo(200);
        assertThat(balanceOf(nec)).isEqualByComparingTo("0");
        assertThat(balanceOf(play)).isEqualByComparingTo("-40000");
        assertBalanceMatchesEntries(nec);
        assertBalanceMatchesEntries(play);
        // doing it again changes nothing
        patch(user, "/api/v1/transactions/" + txId, "{\"jarId\":\"" + play + "\"}");
        assertThat(balanceOf(play)).isEqualByComparingTo("-40000");
    }

    @Test
    void recategorize_rejectsWhatCannotBeChanged() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        UUID edu = jarId(user, "EDU");
        String incomeId = post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "1000"))
                .json()
                .get("transaction")
                .get("id")
                .asString();
        post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "1000"));
        String transferId = post(user, "/api/v1/jars/transfers", newKey(), transferBody(nec, edu, "500"))
                .json()
                .get("transaction")
                .get("id")
                .asString();

        assertThat(patch(user, "/api/v1/transactions/" + incomeId, "{\"jarId\":\"" + edu + "\"}")
                        .status())
                .isEqualTo(422);
        assertThat(patch(user, "/api/v1/transactions/" + transferId, "{\"categoryId\":\"" + CATEGORY_FOOD + "\"}")
                        .status())
                .isEqualTo(422);
    }

    // ---------------------------------------------------------------- jar management

    @Test
    void createJar_needsRoomInTheBudget() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");

        // the six default jars already use 100%
        assertThat(post(user, "/api/v1/jars", null, "{\"name\":\"Du lịch\",\"targetPct\":5}")
                        .status())
                .isEqualTo(422);
        assertThat(patch(user, "/api/v1/jars/" + nec, "{\"targetPct\":50}").status())
                .isEqualTo(200);

        Resp created = post(user, "/api/v1/jars", null, "{\"name\":\"Du lịch\",\"targetPct\":5}");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().get("code").asString()).isEqualTo("CUSTOM");
        assertThat(post(user, "/api/v1/jars", null, "{\"name\":\"Một nữa\",\"targetPct\":1}")
                        .status())
                .isEqualTo(422);
        assertThat(get(user, "/api/v1/jars").json()).hasSize(7);
    }

    @Test
    void updateJar_validatesTheShareAndTheName() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");

        assertThat(patch(user, "/api/v1/jars/" + nec, "{\"targetPct\":101}").status())
                .isEqualTo(422);
        assertThat(patch(user, "/api/v1/jars/" + nec, "{\"targetPct\":-1}").status())
                .isEqualTo(422);
        assertThat(patch(user, "/api/v1/jars/" + nec, "{\"name\":\"\"}").status())
                .isEqualTo(422);
        Resp renamed = patch(user, "/api/v1/jars/" + nec, "{\"name\":\"  Chi tiêu chính  \"}");
        assertThat(renamed.json().get("name").asString()).isEqualTo("Chi tiêu chính");
        assertThat(renamed.json().get("targetPct").decimalValue()).isEqualByComparingTo("55"); // unchanged
    }

    @Test
    void archiveJar_needsAnEmptyJar_hidesItAndFreesItsShare() {
        JwtRequestPostProcessor user = newUser();
        UUID give = jarId(user, "GIVE");
        post(user, "/api/v1/transactions", newKey(), incomeBody(give, "100"));

        assertThat(delete(user, "/api/v1/jars/" + give).status()).isEqualTo(422); // still holds money

        spend(user, give, "100");
        assertThat(delete(user, "/api/v1/jars/" + give).status()).isEqualTo(204);
        assertThat(get(user, "/api/v1/jars").json()).hasSize(5);
        assertThat(spend(user, give, "1").status())
                .as("posting to an archived jar")
                .isEqualTo(422);
        // GIVE's 5% is free again
        assertThat(post(user, "/api/v1/jars", null, "{\"name\":\"Mới\",\"targetPct\":5}")
                        .status())
                .isEqualTo(201);
    }

    @Test
    void twoSimultaneousJarCreations_cannotBothTakeTheLastFreeShare() throws Exception {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        patch(user, "/api/v1/jars/" + nec, "{\"targetPct\":45}"); // 10% free: room for one 10% jar, not two

        List<Resp> responses =
                runInParallel(2, () -> post(user, "/api/v1/jars", null, "{\"name\":\"Hũ\",\"targetPct\":10}"));

        assertThat(responses).extracting(Resp::status).containsExactlyInAnyOrder(201, 422);
    }

    // ------------------------------------------------------------------ users apart (IDOR)

    @Test
    void anotherUser_cannotTouchMyJarsOrTransactions_everythingIs404() {
        JwtRequestPostProcessor alice = newUser();
        JwtRequestPostProcessor bob = newUser();
        UUID aliceNec = jarId(alice, "NEC");
        UUID aliceEdu = jarId(alice, "EDU");
        UUID bobNec = jarId(bob, "NEC");
        post(alice, "/api/v1/transactions", newKey(), incomeBody(aliceNec, "1000000"));
        String aliceTx = spend(alice, aliceNec, "1000")
                .json()
                .get("transaction")
                .get("id")
                .asString();

        assertThat(patch(bob, "/api/v1/jars/" + aliceNec, "{\"name\":\"hacked\"}")
                        .status())
                .as("rename")
                .isEqualTo(404);
        assertThat(delete(bob, "/api/v1/jars/" + aliceNec).status())
                .as("archive")
                .isEqualTo(404);
        assertThat(post(bob, "/api/v1/transactions", newKey(), expenseBody(aliceNec, "1", CATEGORY_FOOD, null))
                        .status())
                .as("spend from her jar")
                .isEqualTo(404);
        assertThat(post(bob, "/api/v1/transactions", newKey(), incomeBody(aliceNec, "1"))
                        .status())
                .as("pay into her jar")
                .isEqualTo(404);
        assertThat(post(bob, "/api/v1/jars/transfers", newKey(), transferBody(aliceNec, bobNec, "1"))
                        .status())
                .as("transfer out of her jar")
                .isEqualTo(404);
        assertThat(post(bob, "/api/v1/jars/transfers", newKey(), transferBody(bobNec, aliceEdu, "1"))
                        .status())
                .as("transfer into her jar")
                .isEqualTo(404);
        assertThat(patch(bob, "/api/v1/transactions/" + aliceTx, "{\"categoryId\":\"" + CATEGORY_TRANSPORT + "\"}")
                        .status())
                .as("edit her transaction")
                .isEqualTo(404);
        assertThat(get(bob, "/api/v1/transactions").json().get("items"))
                .as("her rows are invisible")
                .isEmpty();
        assertThat(get(bob, "/api/v1/transactions?jarId=" + aliceNec).json().get("items"))
                .isEmpty();
        assertThat(get(bob, "/api/v1/jars").json())
                .extracting(j -> j.get("id").asString())
                .doesNotContain(aliceNec.toString());

        assertThat(balanceOf(aliceNec)).as("her balance never moved").isEqualByComparingTo("999000");
    }

    @Test
    void aSomeoneElsesCustomCategory_isNotUsable() {
        JwtRequestPostProcessor alice = newUser();
        UUID aliceNec = jarId(alice, "NEC");
        UUID foreignCategory = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO categories (id, user_id, name, kind) SELECT ?, user_id, 'Bí mật', 'EXPENSE' FROM jars WHERE id = ?")
                .params(foreignCategory, aliceNec)
                .update();
        JwtRequestPostProcessor bob = newUser();
        UUID bobNec = jarId(bob, "NEC");

        assertThat(post(bob, "/api/v1/transactions", newKey(), expenseBody(bobNec, "1", foreignCategory, null))
                        .status())
                .isEqualTo(404);
        assertThat(get(bob, "/api/v1/categories").json())
                .extracting(c -> c.get("name").asString())
                .doesNotContain("Bí mật");
    }

    @Test
    void everyLedgerEndpoint_requiresAToken() {
        UUID anyId = UUID.randomUUID();

        assertThat(get(null, "/api/v1/jars").status()).isEqualTo(401);
        assertThat(get(null, "/api/v1/categories").status()).isEqualTo(401);
        assertThat(get(null, "/api/v1/transactions").status()).isEqualTo(401);
        assertThat(get(null, "/api/v1/balances/daily").status()).isEqualTo(401);
        assertThat(post(null, "/api/v1/transactions", newKey(), "{}").status()).isEqualTo(401);
        assertThat(post(null, "/api/v1/jars/transfers", newKey(), "{}").status())
                .isEqualTo(401);
        assertThat(patch(null, "/api/v1/jars/" + anyId, "{}").status()).isEqualTo(401);
        assertThat(delete(null, "/api/v1/jars/" + anyId).status()).isEqualTo(401);
        assertThat(patch(null, "/api/v1/transactions/" + anyId, "{}").status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    private int count(JwtRequestPostProcessor user, String query) {
        return get(user, "/api/v1/transactions?" + query).json().get("items").size();
    }

    private static String backdated(String type, UUID jarId, String amount, LocalDate day) {
        String when = day + "T05:00:00Z"; // noon in Vietnam, so the Vietnam date equals the UTC date
        return "{\"type\":\"" + type + "\",\"amount\":" + amount + ",\"jarId\":\"" + jarId + "\",\"occurredAt\":\""
                + when + "\"}";
    }

    private List<Resp> runInParallel(int count, java.util.function.Supplier<Resp> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Resp>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.get();
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
