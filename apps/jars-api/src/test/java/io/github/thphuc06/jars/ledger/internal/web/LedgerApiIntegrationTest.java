package io.github.thphuc06.jars.ledger.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.identity.CurrentUser;
import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.ledger.TransactionPosted;
import io.github.thphuc06.jars.ledger.TransactionRecategorized;
import io.github.thphuc06.jars.ledger.TransactionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The ledger HTTP API end to end on PostgreSQL 17 (flow F2, business rules BR-2 and BR-5). The identity module is
 * replaced by a mock of its PUBLIC API: this test is about the ledger, not about Cognito.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class LedgerApiIntegrationTest {

    static final SimpleGrantedAuthority READ = new SimpleGrantedAuthority("SCOPE_jars-api/read");
    static final SimpleGrantedAuthority WRITE = new SimpleGrantedAuthority("SCOPE_jars-api/write");
    static final String SALARY = "01900000-0000-7000-8000-000000000101"; // INCOME, seeded by Flyway V3
    static final String FOOD = "01900000-0000-7000-8000-000000000001"; // EXPENSE

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    @MockitoBean
    IdentityApi identity;

    UUID userId;

    @BeforeEach
    void signIn() {
        userId = insertUser();
        given(identity.currentUser()).willAnswer(call -> new CurrentUser(userId, "u@example.com", null, "vi-VN", null));
    }

    @Test
    void getJars_firstCall_createsTheDefaultsOnDemand_andHidesPool() {
        assertThat(get("/api/v1/jars")).hasStatus(HttpStatus.OK).bodyJson().isLenientlyEqualTo("""
                        {"jars": [{"code": "NEC", "targetPct": 55.00, "balance": "0.00"},
                                  {"code": "FFA"}, {"code": "LTSS"}, {"code": "EDU"}, {"code": "PLAY"},
                                  {"code": "GIVE", "targetPct": 5.00}],
                         "unallocated": "0.00", "total": "0.00", "currency": "VND"}
                        """);
        assertThat(jarCount()).isEqualTo(7); // POOL exists, it is only hidden
    }

    @Test
    void income_withoutJar_landsInPool() {
        MvcTestResult result = post("/api/v1/transactions", """
                {"type": "INCOME", "amount": "15000000", "categoryId": "%s", "note": "Lương tháng 10"}
                """.formatted(SALARY));

        assertThat(result).hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo("""
                        {"transaction": {"type": "INCOME", "amount": "15000000.00", "currency": "VND"},
                         "jars": [{"code": "POOL", "balance": "15000000.00"}]}
                        """);
        assertThat(result.getResponse().getHeader("Location")).startsWith("/api/v1/transactions/");
        assertThat(get("/api/v1/jars")).bodyJson().isLenientlyEqualTo("""
                        {"unallocated": "15000000.00", "total": "15000000.00"}
                        """);
    }

    @Test
    void incomeAllocateSpend_balancesAddUp_andEveryMovementIsInTheLedger() {
        post("/api/v1/transactions", "{\"type\": \"INCOME\", \"amount\": 10000000}");
        assertThat(post("/api/v1/jars/transfers", """
                        {"fromJarId": "%s", "toJarId": "%s", "amount": "1000000"}
                        """.formatted(jarId("POOL"), jarId("PLAY"))))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"transaction": {"type": "JAR_TRANSFER"},
                         "jars": [{"code": "POOL", "balance": "9000000.00"}, {"code": "PLAY", "balance": "1000000.00"}]}
                        """);

        assertThat(post("/api/v1/transactions", """
                        {"type": "EXPENSE", "amount": "85000.00", "jarId": "%s", "categoryId": "%s",
                         "counterparty": "Phở 24"}
                        """.formatted(jarId("PLAY"), FOOD)))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"jars": [{"code": "PLAY", "balance": "915000.00"}]}
                        """);

        assertThat(get("/api/v1/jars")).bodyJson().isLenientlyEqualTo("""
                        {"unallocated": "9000000.00", "total": "9915000.00"}
                        """);
        assertThat(jarsWhoseBalanceDiffersFromTheirEntries()).isEmpty(); // ADR-0014 invariant
        assertThat(events.stream(TransactionPosted.class)
                        .filter(e -> e.userId().equals(userId))
                        .map(TransactionPosted::type))
                .containsExactly(TransactionType.INCOME, TransactionType.JAR_TRANSFER, TransactionType.EXPENSE);
    }

    @Test
    void expense_moreThanTheJarHolds_is422_andWritesNothing() {
        get("/api/v1/jars"); // creates the jars, all empty

        assertProblem(
                post("/api/v1/transactions", expense(jarId("PLAY"), "50000")),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "insufficient-jar-balance");
        assertThat(rows("transactions")).isZero();
        assertThat(rows("jar_entries")).isZero();
    }

    @Test
    void expense_fromPool_is422() {
        get("/api/v1/jars");

        assertProblem(
                post("/api/v1/transactions", expense(jarId("POOL"), "1000")),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "expense-from-pool");
    }

    @Test
    void expense_withoutJar_is422() {
        assertProblem(
                post("/api/v1/transactions", "{\"type\": \"EXPENSE\", \"amount\": \"1000\"}"),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "expense-requires-jar");
    }

    @Test
    void jarOfAnotherUser_is404_asIfItDidNotExist() {
        UUID victim = insertUser();
        UUID victimJar = UUID.randomUUID();
        jdbc.sql("INSERT INTO jars (id, user_id, code, name, target_pct) VALUES (?, ?, 'PLAY', 'Play', 10)")
                .params(victimJar, victim)
                .update();

        assertProblem(post("/api/v1/transactions", expense(victimJar, "1000")), HttpStatus.NOT_FOUND, "jar-not-found");
        assertThat(rows("transactions")).isZero();
    }

    @Test
    void incomeWithAnExpenseCategory_is422() {
        assertProblem(
                post("/api/v1/transactions", """
                        {"type": "INCOME", "amount": "1000", "categoryId": "%s"}
                        """.formatted(FOOD)),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "category-kind-mismatch");
    }

    @Test
    void amountWithDecimals_isAValidationError() {
        MvcTestResult result = post("/api/v1/transactions", "{\"type\": \"INCOME\", \"amount\": \"85000.50\"}");

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "validation-failed");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("amount");
    }

    @Test
    void transferThroughTheTransactionsEndpoint_is422() {
        assertProblem(
                post("/api/v1/transactions", "{\"type\": \"JAR_TRANSFER\", \"amount\": \"1000\"}"),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "use-transfer-endpoint");
    }

    @Test
    void transferToTheSameJar_is422() {
        get("/api/v1/jars");
        UUID play = jarId("PLAY");

        assertProblem(
                post("/api/v1/jars/transfers", """
                        {"fromJarId": "%s", "toJarId": "%s", "amount": "1000"}
                        """.formatted(play, play)),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "same-jar-transfer");
    }

    @Test
    void postingWithAReadOnlyToken_is403() {
        assertThat(mvc.post()
                        .uri("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"INCOME\", \"amount\": \"1000\"}")
                        .with(jwt().authorities(READ))
                        .exchange())
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    // --- Idempotency-Key (P1.6) ---

    @Test
    void sameKeyAndBodyTwice_recordsOnce_andReplaysTheIdenticalResponse() throws Exception {
        UUID key = UUID.randomUUID();
        String income = "{\"type\": \"INCOME\", \"amount\": \"500000\"}";

        MvcTestResult first = post("/api/v1/transactions", key, income);
        MvcTestResult again = post("/api/v1/transactions", key, income); // e.g. the client timed out and re-sent

        assertThat(first).hasStatus(HttpStatus.CREATED).doesNotContainHeader("Idempotent-Replayed");
        assertThat(again).hasStatus(HttpStatus.CREATED).hasHeader("Idempotent-Replayed", "true");
        assertThat(again.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(again.getResponse().getHeader("Location"))
                .isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(rows("transactions")).isEqualTo(1);
        assertThat(get("/api/v1/jars"))
                .bodyJson()
                .extractingPath("$.unallocated")
                .isEqualTo("500000.00");
    }

    @Test
    void sameKey_amountWrittenDifferently_isStillTheSameRequest() {
        UUID key = UUID.randomUUID();

        post("/api/v1/transactions", key, "{\"type\": \"INCOME\", \"amount\": 85000}");

        assertThat(post("/api/v1/transactions", key, "{\"type\": \"INCOME\", \"amount\": \"85000.00\"}"))
                .hasStatus(HttpStatus.CREATED)
                .hasHeader("Idempotent-Replayed", "true");
        assertThat(rows("transactions")).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentBody_is422_andRecordsNothingNew() {
        UUID key = UUID.randomUUID();
        post("/api/v1/transactions", key, "{\"type\": \"INCOME\", \"amount\": \"1000\"}");

        assertProblem(
                post("/api/v1/transactions", key, "{\"type\": \"INCOME\", \"amount\": \"2000\"}"),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "idempotency-key-reused");
        assertThat(rows("transactions")).isEqualTo(1);
    }

    @Test
    void sameKeyOnAnotherEndpoint_is422() {
        UUID key = UUID.randomUUID();
        post("/api/v1/transactions", key, "{\"type\": \"INCOME\", \"amount\": \"1000\"}");

        assertProblem(
                post("/api/v1/jars/transfers", key, """
                        {"fromJarId": "%s", "toJarId": "%s", "amount": "1000"}
                        """.formatted(jarId("POOL"), jarId("PLAY"))),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "idempotency-key-reused");
    }

    @Test
    void failedRequest_isNotRemembered_soTheSameKeyWorksOnceTheCauseIsFixed() {
        get("/api/v1/jars");
        UUID key = UUID.randomUUID();
        String spend = expense(jarId("PLAY"), "1000");

        assertProblem(
                post("/api/v1/transactions", key, spend), HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-jar-balance");
        fundPlay("5000");

        // the failed attempt changed nothing and its reservation was rolled back with it
        assertThat(post("/api/v1/transactions", key, spend))
                .hasStatus(HttpStatus.CREATED)
                .doesNotContainHeader("Idempotent-Replayed");
    }

    @Test
    void keysAreScopedPerUser() {
        UUID sharedKey = UUID.randomUUID();
        String income = "{\"type\": \"INCOME\", \"amount\": \"1000\"}";
        post("/api/v1/transactions", sharedKey, income);

        userId = insertUser(); // another user happens to send the same key
        assertThat(post("/api/v1/transactions", sharedKey, income))
                .hasStatus(HttpStatus.CREATED)
                .doesNotContainHeader("Idempotent-Replayed");
    }

    @Test
    void expiredKey_isTakenOverByANewRequest() {
        UUID key = UUID.randomUUID();
        post("/api/v1/transactions", key, "{\"type\": \"INCOME\", \"amount\": \"1000\"}");
        jdbc.sql("UPDATE idempotency_keys SET expires_at = now() - interval '1 second' WHERE idempotency_key = ?")
                .param(key)
                .update();

        assertThat(post("/api/v1/transactions", key, "{\"type\": \"INCOME\", \"amount\": \"2000\"}"))
                .hasStatus(HttpStatus.CREATED)
                .doesNotContainHeader("Idempotent-Replayed");
        assertThat(rows("transactions")).isEqualTo(2);
    }

    @Test
    void missingIdempotencyKey_is400_andNothingIsRecorded() {
        assertThat(mvc.post()
                        .uri("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"INCOME\", \"amount\": \"1000\"}")
                        .with(jwt().authorities(WRITE))
                        .exchange())
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(rows("transactions")).isZero();
    }

    // --- Money Inbox and amendments (P1.7) ---

    @Test
    void listTransactions_showsJarAndCategoryNames_newestFirst() {
        fundPlay("100000");
        post("/api/v1/transactions", expenseWithCategory(jarId("PLAY"), "85000", FOOD));

        assertThat(get("/api/v1/transactions?limit=2"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"items": [{"type": "EXPENSE", "amount": "85000.00",
                                    "jar": {"code": "PLAY", "name": "Hưởng thụ"},
                                    "category": {"name": "Ăn uống"}},
                                   {"type": "JAR_TRANSFER", "jar": {"code": "POOL"}, "toJar": {"code": "PLAY"}}]}
                        """);
        assertThat(get("/api/v1/transactions?limit=2"))
                .bodyJson()
                .extractingPath("$.nextCursor")
                .isNotNull(); // the income is on the next page
    }

    @Test
    void listTransactions_limitOutOfRange_isAValidationError() {
        assertProblem(get("/api/v1/transactions?limit=500"), HttpStatus.UNPROCESSABLE_CONTENT, "validation-failed");
    }

    @Test
    void getTransaction_ofAnotherUser_is404() {
        String location = post("/api/v1/transactions", "{\"type\": \"INCOME\", \"amount\": \"1000\"}")
                .getResponse()
                .getHeader("Location");
        assertThat(get(location)).hasStatus(HttpStatus.OK);

        userId = insertUser();
        assertProblem(get(location), HttpStatus.NOT_FOUND, "transaction-not-found");
    }

    @Test
    void patch_movesAnExpenseToAnotherJar_withAReversingPairOfEntries() {
        fundPlay("100000");
        fundJar("NEC", "50000");
        UUID expenseId = idOf(post("/api/v1/transactions", expense(jarId("PLAY"), "30000")));

        assertThat(patch(expenseId, "{\"jarId\": \"%s\"}".formatted(jarId("NEC"))))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"transaction": {"jarId": "%s", "amount": "30000.00"},
                         "jars": [{"code": "PLAY", "balance": "100000.00"}, {"code": "NEC", "balance": "20000.00"}]}
                        """.formatted(jarId("NEC")));
        assertThat(entryTypesOf(expenseId)).containsExactly("SPEND", "ADJUST", "ADJUST");
        assertThat(jarsWhoseBalanceDiffersFromTheirEntries()).isEmpty();
        assertThat(events.stream(TransactionRecategorized.class)
                        .filter(e -> e.transactionId().equals(expenseId)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.oldJarId()).isEqualTo(jarId("PLAY"));
                    assertThat(e.newJarId()).isEqualTo(jarId("NEC"));
                });
    }

    @Test
    void patch_movingToAJarThatCannotPay_is422_andChangesNothing() {
        fundPlay("100000");
        UUID expenseId = idOf(post("/api/v1/transactions", expense(jarId("PLAY"), "30000")));

        assertProblem(
                patch(expenseId, "{\"jarId\": \"%s\"}".formatted(jarId("EDU"))), // EDU is empty
                HttpStatus.UNPROCESSABLE_CONTENT,
                "insufficient-jar-balance");
        assertThat(entryTypesOf(expenseId)).containsExactly("SPEND");
    }

    @Test
    void patch_incomeAlreadyAllocated_cannotBeTakenBackFromPool() {
        UUID incomeId = idOf(post("/api/v1/transactions", "{\"type\": \"INCOME\", \"amount\": \"100000\"}"));
        post("/api/v1/jars/transfers", """
                {"fromJarId": "%s", "toJarId": "%s", "amount": "100000"}
                """.formatted(jarId("POOL"), jarId("PLAY")));

        assertProblem(
                patch(incomeId, "{\"jarId\": \"%s\"}".formatted(jarId("LTSS"))),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "insufficient-jar-balance");
    }

    @Test
    void patch_categoryAndNote_explicitNullClears_absentKeeps() {
        fundPlay("100000");
        UUID expenseId = idOf(post("/api/v1/transactions", """
                {"type": "EXPENSE", "amount": "1000", "jarId": "%s", "categoryId": "%s", "note": "trà sữa"}
                """.formatted(jarId("PLAY"), FOOD)));

        patch(expenseId, "{\"note\": null}"); // categoryId absent: unchanged

        assertThat(get("/api/v1/transactions/" + expenseId)).bodyJson().isLenientlyEqualTo("""
                        {"category": {"id": "%s"}, "note": null}
                        """.formatted(FOOD));
    }

    @Test
    void patch_categoryOfTheWrongKind_is422() {
        UUID incomeId = idOf(post("/api/v1/transactions", "{\"type\": \"INCOME\", \"amount\": \"1000\"}"));

        assertProblem(
                patch(incomeId, "{\"categoryId\": \"%s\"}".formatted(FOOD)),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "category-kind-mismatch");
    }

    @Test
    void patch_transferJars_is422() {
        fundPlay("1000");
        UUID transferId = idOf(post("/api/v1/jars/transfers", """
                {"fromJarId": "%s", "toJarId": "%s", "amount": "1000"}
                """.formatted(jarId("PLAY"), jarId("NEC"))));

        assertProblem(
                patch(transferId, "{\"jarId\": \"%s\"}".formatted(jarId("EDU"))),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "transfer-jars-immutable");
    }

    @Test
    void patch_transactionOfAnotherUser_is404() {
        UUID incomeId = idOf(post("/api/v1/transactions", "{\"type\": \"INCOME\", \"amount\": \"1000\"}"));

        userId = insertUser();
        assertProblem(patch(incomeId, "{\"note\": \"mine now\"}"), HttpStatus.NOT_FOUND, "transaction-not-found");
    }

    @Test
    void getCategories_listsTheSystemDefaults() {
        assertThat(get("/api/v1/categories"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.items.length()")
                .isEqualTo(15);
    }

    // --- helpers ---

    private MvcTestResult get(String uri) {
        return mvc.get().uri(uri).with(jwt().authorities(READ)).exchange();
    }

    /** Every money-moving POST needs an Idempotency-Key; a fresh one = a new intention of the user. */
    private MvcTestResult post(String uri, String json) {
        return post(uri, UUID.randomUUID(), json);
    }

    private MvcTestResult post(String uri, UUID idempotencyKey, String json) {
        return mvc.post()
                .uri(uri)
                .header("Idempotency-Key", idempotencyKey.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(jwt().authorities(WRITE))
                .exchange();
    }

    private MvcTestResult patch(UUID transactionId, String json) {
        return mvc.patch()
                .uri("/api/v1/transactions/" + transactionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(jwt().authorities(WRITE))
                .exchange();
    }

    private static UUID idOf(MvcTestResult created) {
        String location = created.getResponse().getHeader("Location");
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private static String expenseWithCategory(UUID jarId, String amount, String categoryId) {
        return """
                {"type": "EXPENSE", "amount": "%s", "jarId": "%s", "categoryId": "%s"}
                """.formatted(amount, jarId, categoryId);
    }

    private List<String> entryTypesOf(UUID transactionId) {
        return jdbc.sql("SELECT entry_type FROM jar_entries WHERE transaction_id = ? ORDER BY created_at, amount")
                .param(transactionId)
                .query(String.class)
                .list();
    }

    private void fundJar(String code, String amount) {
        post("/api/v1/transactions", "{\"type\": \"INCOME\", \"amount\": \"%s\"}".formatted(amount));
        post("/api/v1/jars/transfers", """
                {"fromJarId": "%s", "toJarId": "%s", "amount": "%s"}
                """.formatted(jarId("POOL"), jarId(code), amount));
    }

    private void fundPlay(String amount) {
        post("/api/v1/transactions", "{\"type\": \"INCOME\", \"amount\": \"%s\"}".formatted(amount));
        post("/api/v1/jars/transfers", """
                {"fromJarId": "%s", "toJarId": "%s", "amount": "%s"}
                """.formatted(jarId("POOL"), jarId("PLAY"), amount));
    }

    private static String expense(UUID jarId, String amount) {
        return """
                {"type": "EXPENSE", "amount": "%s", "jarId": "%s"}
                """.formatted(amount, jarId);
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result)
                .hasStatus(status)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.type")
                .isEqualTo("https://jars.local/problems/" + code);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(id, "sub-" + id, id + "@example.com")
                .update();
        return id;
    }

    private UUID jarId(String code) {
        return jdbc.sql("SELECT id FROM jars WHERE user_id = ? AND code = ?")
                .params(userId, code)
                .query(UUID.class)
                .single();
    }

    private long jarCount() {
        return jdbc.sql("SELECT count(*) FROM jars WHERE user_id = ?")
                .param(userId)
                .query(Long.class)
                .single();
    }

    private long rows(String table) {
        String sql = switch (table) {
            case "transactions" -> "SELECT count(*) FROM transactions WHERE user_id = ?";
            case "jar_entries" ->
                "SELECT count(*) FROM jar_entries e JOIN jars j ON j.id = e.jar_id WHERE j.user_id = ?";
            default -> throw new IllegalArgumentException(table);
        };
        return jdbc.sql(sql).param(userId).query(Long.class).single();
    }

    private List<UUID> jarsWhoseBalanceDiffersFromTheirEntries() {
        return jdbc.sql("""
                        SELECT j.id FROM jars j LEFT JOIN jar_entries e ON e.jar_id = j.id
                        WHERE j.user_id = ?
                        GROUP BY j.id, j.balance
                        HAVING j.balance <> COALESCE(SUM(e.amount), 0)
                        """).param(userId).query(UUID.class).list();
    }
}
