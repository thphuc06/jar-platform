package io.github.thphuc06.jars.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.UserApiTestSupport;
import io.github.thphuc06.jars.budgeting.internal.service.MonthCloseService;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.JsonNode;

/**
 * The audit log end to end against a real PostgreSQL: what is written for each operation (and what is not), that a
 * rolled back, retried or replayed request leaves exactly the rows the committed changes deserve, and the read API.
 */
class AuditLogApiTest extends UserApiTestSupport {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    ApplicationContext context;

    @Autowired
    MonthCloseService months;

    /** A stored row, read back with plain SQL. */
    record Row(
            UUID id,
            Instant occurredAt,
            String actor,
            String action,
            String entityType,
            UUID entityId,
            String traceId,
            JsonNode payload) {}

    private List<Row> rows(UserId user) {
        return rowsOf(user.toString());
    }

    private List<Row> rowsOf(String actor) {
        return jdbc.sql("""
                        SELECT id, occurred_at, actor, action, entity_type, entity_id, trace_id, payload::text AS payload
                        FROM audit_log WHERE actor = ? ORDER BY occurred_at, id
                        """)
                .param(actor)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class),
                        rs.getString("trace_id"),
                        mapper.readTree(rs.getString("payload"))))
                .list();
    }

    private List<Row> rows(UserId user, String action) {
        return rows(user).stream().filter(r -> r.action().equals(action)).toList();
    }

    private static String traceIdOf(Resp response) {
        return response.header("traceparent").substring(3, 35);
    }

    private static String expense(UUID jar, String amount, String counterparty, String note) {
        return "{\"type\":\"EXPENSE\",\"amount\":" + amount + ",\"jarId\":\"" + jar + "\",\"categoryId\":\""
                + CATEGORY_FOOD + "\",\"counterparty\":\"" + counterparty + "\",\"note\":\"" + note + "\"}";
    }

    // ------------------------------------------------------------ commit: one row

    @Test
    void aCommittedExpense_writesOneRowWithActorActionEntityTraceAndPayload() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        post(user.token(), "/api/v1/transactions", newKey(), incomeBody(nec, "1000000"));
        Instant before = Instant.now().minusSeconds(1);

        Resp spent = post(
                user.token(),
                "/api/v1/transactions",
                newKey(),
                expense(nec, "85000", "Quan Bun Bo Co Ba", "ghi chu rieng tu khong duoc luu"));

        assertThat(spent.status()).isEqualTo(201);
        List<Row> rows = rows(user.id(), "TRANSACTION_POSTED");
        assertThat(rows).as("the income and the expense, one row each").hasSize(2);
        Row row = rows.getLast();
        assertThat(row.actor()).isEqualTo(user.id().toString());
        assertThat(row.entityType()).isEqualTo("TRANSACTION");
        assertThat(row.entityId())
                .isEqualTo(UUID.fromString(
                        spent.json().get("transaction").get("id").asString()));
        assertThat(row.traceId()).as("the id the response and the logs carry").isEqualTo(traceIdOf(spent));
        assertThat(row.occurredAt()).isBetween(before, Instant.now().plusSeconds(1));
        assertThat(row.payload().get("type").asString()).isEqualTo("EXPENSE");
        assertThat(row.payload().get("amount").asString()).isEqualTo("85000.00");
        assertThat(row.payload().get("currency").asString()).isEqualTo("VND");
        assertThat(row.payload().get("jarId").asString()).isEqualTo(nec.toString());
        assertThat(row.payload().get("categoryId").asString()).isEqualTo(CATEGORY_FOOD.toString());
        assertThat(row.payload().get("amount").isString())
                .as("money is a string, never a float")
                .isTrue();
        assertThat(row.payload().toString())
                .as("no counterparty, no note")
                .doesNotContain("Quan Bun")
                .doesNotContain("ghi chu");
    }

    @Test
    void aTransfer_recordsTheAmountAndBothJars() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        post(user.token(), "/api/v1/transactions", newKey(), incomeBody(nec, "500000"));

        Resp moved = post(user.token(), "/api/v1/jars/transfers", newKey(), transferBody(nec, edu, "120000"));

        Row row = rows(user.id(), "JAR_TRANSFERRED").getFirst();
        assertThat(rows(user.id(), "JAR_TRANSFERRED")).hasSize(1);
        assertThat(row.entityType()).isEqualTo("TRANSACTION");
        assertThat(row.entityId().toString())
                .isEqualTo(moved.json().get("transaction").get("id").asString());
        assertThat(row.payload().get("amount").asString()).isEqualTo("120000.00");
        assertThat(row.payload().get("fromJarId").asString()).isEqualTo(nec.toString());
        assertThat(row.payload().get("toJarId").asString()).isEqualTo(edu.toString());
        assertThat(row.traceId()).isEqualTo(traceIdOf(moved));
    }

    @Test
    void recategorizingAnExpenseIntoAnotherJar_recordsTheMoveAndItsReversingEntry() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID play = jarId(user.token(), "PLAY");
        Resp spent = spend(user.token(), nec, "40000");
        String id = spent.json().get("transaction").get("id").asString();

        Resp moved = patch(user.token(), "/api/v1/transactions/" + id, "{\"jarId\":\"" + play + "\"}");
        Resp same = patch(user.token(), "/api/v1/transactions/" + id, "{\"jarId\":\"" + play + "\"}");

        assertThat(moved.status()).isEqualTo(200);
        assertThat(same.status()).isEqualTo(200);
        List<Row> rows = rows(user.id(), "TRANSACTION_RECATEGORIZED");
        assertThat(rows)
                .as("the second patch changed nothing and leaves no row")
                .hasSize(1);
        assertThat(rows.getFirst().entityId().toString()).isEqualTo(id);
        assertThat(rows.getFirst().payload().get("movedBetweenJars").asBoolean())
                .isTrue();
        assertThat(rows.getFirst().payload().get("fromJarId").asString()).isEqualTo(nec.toString());
        assertThat(rows.getFirst().payload().get("toJarId").asString()).isEqualTo(play.toString());
        assertThat(rows.getFirst().payload().get("amount").asString()).isEqualTo("40000.00");
        assertThat(jdbc.sql("SELECT count(*) FROM jar_entries WHERE transaction_id = ? AND entry_type = 'ADJUST'")
                        .param(UUID.fromString(id))
                        .query(Integer.class)
                        .single())
                .as("the reversing entry exists exactly once, like the row")
                .isEqualTo(1);
    }

    @Test
    void jarCreateUpdateAndArchive_areAudited_withoutTheJarName() {
        TestUser user = newTestUser();
        JsonNode created = post(user.token(), "/api/v1/jars", null, "{\"name\":\"Quy Du Lich Rieng\",\"targetPct\":0}")
                .json();
        String id = created.get("id").asString();

        patch(user.token(), "/api/v1/jars/" + id, "{\"name\":\"Ten moi bi mat\"}");
        assertThat(delete(user.token(), "/api/v1/jars/" + id).status()).isEqualTo(204);

        assertThat(rows(user.id()))
                .extracting(Row::action)
                .containsExactly("JAR_CREATED", "JAR_UPDATED", "JAR_ARCHIVED");
        assertThat(rows(user.id())).extracting(Row::entityType).containsOnly("JAR");
        assertThat(rows(user.id())).extracting(r -> r.entityId().toString()).containsOnly(id);
        assertThat(rows(user.id()).get(1).payload().get("renamed").asBoolean()).isTrue();
        assertThat(rows(user.id()).toString()).doesNotContain("Du Lich").doesNotContain("bi mat");
    }

    @Test
    void goalCreateUpdateAndDelete_areAudited_withoutTheGoalName() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        String date = LocalDate.now(VIETNAM).plusMonths(8).toString();
        String id = post(
                        user.token(),
                        "/api/v1/goals",
                        null,
                        "{\"name\":\"Mua nha o Da Lat\",\"targetAmount\":25000000,\"targetDate\":\"" + date
                                + "\",\"fundingJarId\":\"" + nec + "\"}")
                .json()
                .get("id")
                .asString();

        patch(user.token(), "/api/v1/goals/" + id, "{\"name\":\"Mua nha khac\",\"targetAmount\":30000000}");
        delete(user.token(), "/api/v1/goals/" + id);

        List<Row> rows = rows(user.id());
        assertThat(rows).extracting(Row::action).containsExactly("GOAL_CREATED", "GOAL_UPDATED", "GOAL_ARCHIVED");
        assertThat(rows).extracting(Row::entityType).containsOnly("GOAL");
        assertThat(rows.get(0).payload().get("targetAmount").asString()).isEqualTo("25000000.00");
        assertThat(rows.get(0).payload().get("fundingJarId").asString()).isEqualTo(nec.toString());
        assertThat(rows.get(1).payload().get("changed").toString())
                .contains("name")
                .contains("targetAmount");
        assertThat(rows.get(1).payload().get("targetAmount").asString()).isEqualTo("30000000.00");
        assertThat(rows.toString()).doesNotContain("Da Lat").doesNotContain("nha khac");
    }

    @Test
    void profileAndRiskProfileUpdates_areAudited_withoutPersonalData() {
        TestUser user = newTestUser();

        patch(user.token(), "/api/v1/me", "{\"displayName\":\"Nguyen Van Rieng Tu\",\"locale\":\"en-US\"}");
        Resp risk = call(HttpMethod.PUT, "/api/v1/me/risk-profile", user.token(), null, "{\"answers\":[2,3,2,2,1]}");

        assertThat(risk.status()).isEqualTo(200);
        List<Row> rows = rows(user.id());
        assertThat(rows).extracting(Row::action).containsExactly("PROFILE_UPDATED", "RISK_PROFILE_SUBMITTED");
        assertThat(rows.get(0).entityType()).isEqualTo("USER");
        assertThat(rows.get(0).entityId()).isEqualTo(user.id().value());
        assertThat(rows.get(0).payload().get("displayNameChanged").asBoolean()).isTrue();
        assertThat(rows.get(0).payload().get("locale").asString()).isEqualTo("en-US");
        assertThat(rows.get(1).entityType()).isEqualTo("RISK_ASSESSMENT");
        assertThat(rows.get(1).payload().get("score").asLong()).isEqualTo(100);
        assertThat(rows.get(1).payload().get("riskLevel").asString()).isEqualTo("GROWTH");
        assertThat(rows.toString()).doesNotContain("Nguyen").doesNotContain("@");
    }

    @Test
    void anImportIsAuditedOnceWhenItStarts_notOncePerCsvLine() {
        TestUser user = newTestUser();
        Resp created = post(user.token(), "/api/v1/imports", null, "{\"fileName\":\"sao-ke-rieng-tu.csv\"}");
        assertThat(created.status()).isEqualTo(201);
        String importId = created.json().at("/statementImport/id").asString();

        // the file arrives (local profile: the PUT endpoint starts the batch) and its lines are posted by the batch
        String csv = "date,description,amount\n2026-10-02,Ca phe rieng tu,-50000\n2026-10-03,Banh mi,-70000\n"
                + "2026-10-04,Com trua,-90000\n";
        MockHttpServletResponse uploaded = mvc.perform(MockMvcRequestBuilders.put(
                                created.json().at("/upload/url").asString())
                        .with(user.token())
                        .contentType("text/csv")
                        .content(csv.getBytes(StandardCharsets.UTF_8)))
                .getResponse();
        assertThat(uploaded.getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM transactions WHERE user_id = ? AND source = 'IMPORT'")
                        .param(user.id().value())
                        .query(Integer.class)
                        .single())
                .as("the batch posted the three lines")
                .isEqualTo(3);

        List<Row> rows = rows(user.id());
        assertThat(rows.stream().filter(r -> r.action().equals("IMPORT_CREATED")))
                .as("one row for the import")
                .hasSize(1);
        assertThat(rows.stream().filter(r -> r.action().equals("TRANSACTION_POSTED")))
                .as("the imported lines are not audited one by one")
                .isEmpty();
        assertThat(rows.getFirst().entityType()).isEqualTo("STATEMENT_IMPORT");
        assertThat(rows.getFirst().entityId().toString()).isEqualTo(importId);
        assertThat(rows.toString()).doesNotContain("sao-ke").doesNotContain("Ca phe");
    }

    @Test
    void anomalyFeedback_isAudited() {
        TestUser user = newTestUser();
        UUID id = Uuids.v7();
        jdbc.sql("""
                        INSERT INTO anomalies (id, user_id, jar_id, transaction_id, detector, direction, severity,
                                               score, threshold, amount, status, detected_at)
                        VALUES (?, ?, ?, ?, 'MAD', 'UP', 'HIGH', 9, 3.5, 100000, 'OPEN', ?)
                        """)
                .params(
                        id,
                        user.id().value(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
                .update();

        Resp rejected = post(user.token(), "/api/v1/anomalies/" + id + "/feedback", null, "{\"label\":\"MAYBE\"}");
        Resp ok = post(user.token(), "/api/v1/anomalies/" + id + "/feedback", null, "{\"label\":\"EXPECTED\"}");

        assertThat(rejected.status()).isEqualTo(422);
        assertThat(ok.status()).isEqualTo(200);
        List<Row> rows = rows(user.id());
        assertThat(rows).as("the rejected label left no row").hasSize(1);
        assertThat(rows.getFirst().action()).isEqualTo("ANOMALY_FEEDBACK_GIVEN");
        assertThat(rows.getFirst().entityType()).isEqualTo("ANOMALY");
        assertThat(rows.getFirst().entityId()).isEqualTo(id);
        assertThat(rows.getFirst().payload().get("verdict").asString()).isEqualTo("EXPECTED");
    }

    @Test
    void applyingABudgetAllocation_writesTheLedgerRowAndTheDecisionRow_andAReplayAddsNone() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        String key = newKey();
        String body = "{\"amount\":1000000,\"splits\":[{\"jarId\":\"" + nec + "\",\"amount\":600000},{\"jarId\":\""
                + edu + "\",\"amount\":400000}]}";

        Resp first = post(user.token(), "/api/v1/budget/allocations", key, body);
        Resp replay = post(user.token(), "/api/v1/budget/allocations", key, body);

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.header("Idempotency-Replayed")).isEqualTo("true");
        List<Row> rows = rows(user.id());
        assertThat(rows).extracting(Row::action).containsExactlyInAnyOrder("INCOME_ALLOCATED", "ALLOCATION_DECIDED");
        Row income = rows(user.id(), "INCOME_ALLOCATED").getFirst();
        assertThat(income.entityId().toString())
                .isEqualTo(first.json().get("transactionId").asString());
        assertThat(income.payload().get("amount").asString()).isEqualTo("1000000.00");
        assertThat(income.payload().get("jarIds").toString())
                .contains(nec.toString())
                .contains(edu.toString());
        Row decision = rows(user.id(), "ALLOCATION_DECIDED").getFirst();
        assertThat(decision.entityType()).isEqualTo("ALLOCATION_DECISION");
        assertThat(decision.entityId().toString())
                .isEqualTo(first.json().get("decisionId").asString());
        assertThat(decision.payload().get("decision").asString()).isIn("ACCEPTED", "EDITED");
        assertThat(decision.payload().get("transactionId").asString())
                .isEqualTo(first.json().get("transactionId").asString());
    }

    @Test
    void skippingASuggestion_recordsTheDecisionOnce() {
        TestUser user = newTestUser();
        String key = newKey();

        post(user.token(), "/api/v1/budget/allocations", key, "{\"amount\":8000000,\"skip\":true}");
        post(user.token(), "/api/v1/budget/allocations", key, "{\"amount\":8000000,\"skip\":true}");

        List<Row> rows = rows(user.id());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().action()).isEqualTo("ALLOCATION_DECIDED");
        assertThat(rows.getFirst().payload().get("decision").asString()).isEqualTo("SKIPPED");
    }

    @Test
    void readsAreNeverAudited() {
        TestUser user = newTestUser();
        long before = rows(user.id()).size();

        get(user.token(), "/api/v1/jars");
        get(user.token(), "/api/v1/transactions");
        get(user.token(), "/api/v1/me");
        get(user.token(), "/api/v1/goals");
        get(user.token(), "/api/v1/audit-log");
        post(user.token(), "/api/v1/budget/allocation-suggestions", null, "{\"amount\":1000000}");

        assertThat(rows(user.id())).hasSize((int) before);
    }

    // -------------------------------------------------- rollback, retry, replay

    @Test
    void rejectedRequests_leaveNoRow_andTheKeyCanBeUsedAgain() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        String key = newKey();

        assertThat(post(user.token(), "/api/v1/jars/transfers", newKey(), transferBody(nec, edu, "1000"))
                        .status())
                .as("no money in the jar")
                .isEqualTo(422);
        assertThat(post(
                                user.token(),
                                "/api/v1/transactions",
                                newKey(),
                                expenseBody(UUID.randomUUID(), "1", CATEGORY_FOOD, null))
                        .status())
                .as("unknown jar")
                .isEqualTo(404);
        assertThat(post(user.token(), "/api/v1/transactions", key, expenseBody(nec, "100", CATEGORY_SALARY, null))
                        .status())
                .as("income category on an expense")
                .isEqualTo(422);
        assertThat(patch(user.token(), "/api/v1/jars/" + UUID.randomUUID(), "{\"name\":\"x\"}")
                        .status())
                .isEqualTo(404);

        assertThat(rows(user.id()))
                .as("every attempt was rolled back, so was its audit row")
                .isEmpty();

        assertThat(post(user.token(), "/api/v1/transactions", key, expenseBody(nec, "100", CATEGORY_FOOD, null))
                        .status())
                .isEqualTo(201);
        assertThat(rows(user.id())).hasSize(1);
    }

    @Test
    void theSameIdempotencyKeyTwice_writesOneRow() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        String key = newKey();
        String body = expenseBody(nec, "120000", CATEGORY_FOOD, null);

        post(user.token(), "/api/v1/transactions", key, body);
        Resp replay = post(user.token(), "/api/v1/transactions", key, body);
        Resp reused = post(user.token(), "/api/v1/transactions", key, expenseBody(nec, "999", CATEGORY_FOOD, null));

        assertThat(replay.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(reused.status()).as("the key belongs to another request").isEqualTo(422);
        assertThat(rows(user.id())).hasSize(1);
    }

    @Test
    void theSameIdempotencyKeyConcurrently_writesOneRow() throws Exception {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        String key = newKey();
        String body = expenseBody(nec, "10000", CATEGORY_FOOD, null);

        List<Resp> responses = runConcurrently(8, i -> post(user.token(), "/api/v1/transactions", key, body));

        assertThat(responses).extracting(Resp::status).containsOnly(201);
        assertThat(rows(user.id())).as("eight requests, one posting, one row").hasSize(1);
    }

    @Test
    void concurrentSpendingFromOneJar_leavesExactlyOneRowPerAcceptedPosting() throws Exception {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        int writers = 12;

        // twelve expenses fight over one jar row: the losers of the optimistic-lock race are retried
        List<Resp> responses = runConcurrently(writers, i -> spend(user.token(), nec, "1000"));

        Set<String> accepted = responses.stream()
                .filter(r -> r.status() == 201)
                .map(r -> r.json().get("transaction").get("id").asString())
                .collect(Collectors.toSet());
        assertThat(responses).extracting(Resp::status).isSubsetOf(201, 409);
        assertThat(accepted).isNotEmpty();
        List<Row> rows = rows(user.id());
        assertThat(rows).as("a retried attempt must not leave a row of its own").hasSize(accepted.size());
        assertThat(rows.stream().map(r -> r.entityId().toString()).collect(Collectors.toSet()))
                .as("the rows are exactly the accepted postings")
                .isEqualTo(accepted);
    }

    // -------------------------------------------------------------- the system

    @Test
    void closingAMonth_isAuditedAsTheSystem_andIsNotListedToTheUser() {
        TestUser user = newTestUser();
        YearMonth lastMonth = YearMonth.now(VIETNAM).minusMonths(1);
        UUID nec = jarId(user.token(), "NEC");
        String at =
                lastMonth.atDay(15).atTime(12, 0).atZone(VIETNAM).toInstant().toString();
        post(
                user.token(),
                "/api/v1/budget/allocations",
                newKey(),
                "{\"amount\":100000,\"occurredAt\":\"" + at + "\",\"splits\":[{\"jarId\":\"" + nec
                        + "\",\"amount\":100000}]}");

        boolean closed = months.closeMonth(user.id(), lastMonth);
        boolean again = months.closeMonth(user.id(), lastMonth);

        assertThat(closed).isTrue();
        assertThat(again).isFalse();
        List<Row> system = rowsOf("system").stream()
                .filter(r ->
                        r.action().equals("MONTH_CLOSED") && user.id().value().equals(r.entityId()))
                .toList();
        assertThat(system).as("closed once, repeated once, one row").hasSize(1);
        assertThat(system.getFirst().entityType()).isEqualTo("USER");
        assertThat(system.getFirst().payload().get("month").asString()).isEqualTo(lastMonth.toString());
        assertThat(get(user.token(), "/api/v1/audit-log").json().get("items").toString())
                .as("system rows are not the caller's")
                .doesNotContain("MONTH_CLOSED");
    }

    // ------------------------------------------------------------- the read API

    @Test
    void theAuditLog_showsOnlyTheCallersOwnRows() {
        TestUser a = newTestUser();
        TestUser b = newTestUser();
        UUID necA = jarId(a.token(), "NEC");
        UUID necB = jarId(b.token(), "NEC");
        Resp aSpent = spend(a.token(), necA, "1000");
        spend(b.token(), necB, "2000");
        spend(b.token(), necB, "3000");

        JsonNode forA = get(a.token(), "/api/v1/audit-log").json();
        JsonNode forB = get(b.token(), "/api/v1/audit-log").json();

        assertThat(forA.get("items")).hasSize(1);
        assertThat(forA.get("items").get(0).get("entityId").asString())
                .isEqualTo(aSpent.json().get("transaction").get("id").asString());
        assertThat(forA.get("items").get(0).get("action").asString()).isEqualTo("TRANSACTION_POSTED");
        assertThat(forA.get("items").get(0).get("entityType").asString()).isEqualTo("TRANSACTION");
        assertThat(forA.get("items").get(0).get("payload").get("amount").asString())
                .isEqualTo("1000.00");
        assertThat(forA.get("nextCursor").isNull()).isTrue();
        assertThat(forB.get("items")).hasSize(2);
        assertThat(forB.toString()).doesNotContain(necA.toString());
        assertThat(forA.toString()).doesNotContain(necB.toString());
        assertThat(forA.get("items").get(0).has("actor"))
                .as("the caller is not echoed back")
                .isFalse();
    }

    @Test
    void aCursorOfSomeoneElse_revealsNothingOfTheirs() {
        TestUser a = newTestUser();
        TestUser b = newTestUser();
        UUID necA = jarId(a.token(), "NEC");
        UUID necB = jarId(b.token(), "NEC");
        for (int i = 0; i < 4; i++) {
            spend(a.token(), necA, "1000");
        }
        spend(b.token(), necB, "5000");
        String cursorOfA = get(a.token(), "/api/v1/audit-log?limit=2")
                .json()
                .get("nextCursor")
                .asString();

        JsonNode asB =
                get(b.token(), "/api/v1/audit-log?limit=50&cursor=" + cursorOfA).json();

        assertThat(asB.get("items").toString()).doesNotContain(necA.toString());
        assertThat(asB.get("items").size()).isLessThanOrEqualTo(1);
    }

    @Test
    void theAuditLog_filtersByActionAndEntityType() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        UUID edu = jarId(user.token(), "EDU");
        post(user.token(), "/api/v1/transactions", newKey(), incomeBody(nec, "500000"));
        post(user.token(), "/api/v1/jars/transfers", newKey(), transferBody(nec, edu, "1000"));
        post(user.token(), "/api/v1/jars", null, "{\"name\":\"Mot hu moi\",\"targetPct\":0}");

        assertThat(actions(get(user.token(), "/api/v1/audit-log").json()))
                .containsExactly("JAR_CREATED", "JAR_TRANSFERRED", "TRANSACTION_POSTED");
        assertThat(actions(get(user.token(), "/api/v1/audit-log?action=JAR_TRANSFERRED")
                        .json()))
                .containsExactly("JAR_TRANSFERRED");
        assertThat(actions(get(user.token(), "/api/v1/audit-log?entityType=TRANSACTION")
                        .json()))
                .containsExactly("JAR_TRANSFERRED", "TRANSACTION_POSTED");
        assertThat(actions(get(user.token(), "/api/v1/audit-log?entityType=TRANSACTION&action=JAR_CREATED")
                        .json()))
                .as("a valid filter that matches nothing is an empty page, not an error")
                .isEmpty();
        assertThat(get(user.token(), "/api/v1/audit-log?action=GOAL_ARCHIVED")
                        .json()
                        .get("nextCursor")
                        .isNull())
                .isTrue();
    }

    @Test
    void badFiltersAndParameters_areProblemJson400() {
        TestUser user = newTestUser();

        for (String uri : List.of(
                "/api/v1/audit-log?action=NO_SUCH_ACTION",
                "/api/v1/audit-log?entityType=NO_SUCH_TYPE",
                "/api/v1/audit-log?limit=0",
                "/api/v1/audit-log?limit=101",
                "/api/v1/audit-log?limit=abc",
                "/api/v1/audit-log?cursor=not-a-cursor")) {
            Resp response = get(user.token(), uri);
            assertThat(response.status()).as(uri).isEqualTo(400);
            assertThat(response.raw().getContentType()).as(uri).contains("application/problem+json");
            assertThat(response.json().get("status").asInt()).isEqualTo(400);
            assertThat(response.json().has("traceId")).as(uri).isTrue();
        }
    }

    @Test
    void theAuditLog_needsAToken() {
        Resp response = get(null, "/api/v1/audit-log");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.raw().getContentType()).contains("application/problem+json");
    }

    @Test
    void pages_runNewestFirst_andACursorSurvivesNewRows() {
        TestUser user = newTestUser();
        UUID nec = jarId(user.token(), "NEC");
        List<String> oldestFirst = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            oldestFirst.add(spend(user.token(), nec, String.valueOf(i * 1000))
                    .json()
                    .get("transaction")
                    .get("id")
                    .asString());
        }

        JsonNode page1 = get(user.token(), "/api/v1/audit-log?limit=3").json();
        // rows written after the first page was served must not shift the pages that follow
        spend(user.token(), nec, "9000");
        spend(user.token(), nec, "9001");
        JsonNode page2 = get(
                        user.token(),
                        "/api/v1/audit-log?limit=3&cursor="
                                + page1.get("nextCursor").asString())
                .json();
        JsonNode page3 = get(
                        user.token(),
                        "/api/v1/audit-log?limit=3&cursor="
                                + page2.get("nextCursor").asString())
                .json();

        List<String> newestFirst = oldestFirst.reversed();
        assertThat(entityIds(page1)).containsExactlyElementsOf(newestFirst.subList(0, 3));
        assertThat(entityIds(page2)).containsExactlyElementsOf(newestFirst.subList(3, 6));
        assertThat(entityIds(page3)).containsExactly(newestFirst.get(6));
        assertThat(page3.get("nextCursor").isNull())
                .as("the last page has no cursor")
                .isTrue();
        assertThat(get(user.token(), "/api/v1/audit-log?limit=100").json().get("items"))
                .hasSize(9);
    }

    // ------------------------------------------------------ proxies are real

    @Test
    void everyBeanWithAnAuditedMethod_isACglibProxy() {
        int audited = 0;
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(type);
            boolean hasAudited =
                    Arrays.stream(userClass.getDeclaredMethods()).anyMatch(m -> m.isAnnotationPresent(Audited.class));
            if (hasAudited) {
                audited++;
                assertThat(AopUtils.isCglibProxy(context.getBean(name)))
                        .as("bean %s (%s) has @Audited methods", name, userClass.getSimpleName())
                        .isTrue();
            }
        }
        assertThat(audited)
                .as("the beans that carry @Audited methods were found")
                .isGreaterThanOrEqualTo(8);
    }

    @Test
    void theNamesInTheLogAreTheNamesInTheCode() {
        // every action that is declared is used by some @Audited method (no dead names in the vocabulary)
        Set<AuditAction> used = new HashSet<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null) {
                continue;
            }
            for (var method : ClassUtils.getUserClass(type).getDeclaredMethods()) {
                Audited annotation = method.getAnnotation(Audited.class);
                if (annotation != null) {
                    used.add(annotation.value());
                }
            }
        }
        assertThat(used).containsExactlyInAnyOrder(AuditAction.values());
    }

    // ----------------------------------------------------------------- helpers

    private static List<String> actions(JsonNode page) {
        List<String> actions = new ArrayList<>();
        page.get("items").forEach(i -> actions.add(i.get("action").asString()));
        return actions;
    }

    private static List<String> entityIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(i -> ids.add(i.get("entityId").asString()));
        return ids;
    }

    /** Releases {@code count} tasks at the same instant and collects their responses. */
    private List<Resp> runConcurrently(int count, IntFunction<Resp> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Resp>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.apply(index);
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
