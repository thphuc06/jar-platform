package io.github.thphuc06.jars.goals.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.identity.CurrentUser;
import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.shared.BusinessTime;
import java.time.Clock;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Goals CRUD end to end (business-rules BR-6) with the REAL ledger behind {@code LedgerApi}: jars are created and
 * funded through the ledger HTTP API, as a client would. Only the identity module is mocked (its public API).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GoalsIntegrationTest {

    static final SimpleGrantedAuthority READ = new SimpleGrantedAuthority("SCOPE_jars-api/read");
    static final SimpleGrantedAuthority WRITE = new SimpleGrantedAuthority("SCOPE_jars-api/write");
    static final String NEXT_YEAR =
            BusinessTime.today(Clock.systemUTC()).plusYears(1).toString();

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    IdentityApi identity;

    UUID userId;

    @BeforeEach
    void signIn() {
        userId = insertUser();
        given(identity.currentUser()).willAnswer(call -> new CurrentUser(userId, "u@example.com", null, "vi-VN", null));
        call(mvc.get().uri("/api/v1/jars"), READ); // the ledger creates the default jars
    }

    @Test
    void createWithoutJar_thenList() {
        MvcTestResult created = create("Laptop mới", "30000000", NEXT_YEAR, null);

        assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo("""
                        {"name": "Laptop mới", "targetAmount": "30000000.00", "status": "ACTIVE",
                         "fundingJar": null, "progressPct": null}
                        """);
        assertThat(created.getResponse().getHeader("Location")).startsWith("/api/v1/goals/");
        assertThat(list()).bodyJson().extractingPath("$.items.length()").isEqualTo(1);
    }

    @Test
    void fundingJar_showsItsBalanceAndTheProgress_roundedDown() {
        fundPlay("249999");

        assertThat(create("Du lịch Đà Lạt", "1000000", NEXT_YEAR, jarId("PLAY")))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"fundingJar": {"code": "PLAY", "balance": "249999.00", "archived": false},
                         "progressPct": 24}
                        """);
    }

    @Test
    void list_isOrderedByDeadline_withTheJarOfEachGoal() {
        LocalDate today = BusinessTime.today(Clock.systemUTC());
        create("Xa", "1000", today.plusYears(3).toString(), jarId("PLAY"));
        create("Gần", "1000", today.plusMonths(1).toString(), jarId("NEC"));

        assertThat(list()).bodyJson().isLenientlyEqualTo("""
                        {"items": [{"name": "Gần", "fundingJar": {"code": "NEC"}},
                                   {"name": "Xa", "fundingJar": {"code": "PLAY"}}]}
                        """);
    }

    @Test
    void targetDateTodayOrEarlier_isRejected_inVietnamTime() {
        String todayInVietnam = BusinessTime.today(Clock.systemUTC()).toString();

        assertProblem(
                create("Hôm nay", "1000", todayInVietnam, null),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "target-date-not-in-future");
    }

    @Test
    void poolCannotFundAGoal() {
        assertProblem(
                create("Từ POOL", "1000", NEXT_YEAR, jarId("POOL")),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "funding-jar-is-pool");
    }

    @Test
    void jarOfAnotherUser_isNotFound() {
        UUID victimJar = UUID.randomUUID();
        jdbc.sql("INSERT INTO jars (id, user_id, code, name, target_pct) VALUES (?, ?, 'PLAY', 'Play', 10)")
                .params(victimJar, insertUser())
                .update();

        assertProblem(create("Của người khác", "1000", NEXT_YEAR, victimJar), HttpStatus.NOT_FOUND, "jar-not-found");
    }

    @Test
    void patch_renamesAndMarksAchieved_absentFieldsStay() {
        UUID goalId = idOf(create("Quỹ khẩn cấp", "5000000", NEXT_YEAR, jarId("LTSS")));

        assertThat(patch(goalId, "{\"name\": \"Quỹ dự phòng\", \"status\": \"ACHIEVED\"}"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"name": "Quỹ dự phòng", "status": "ACHIEVED", "targetAmount": "5000000.00",
                         "fundingJar": {"code": "LTSS"}}
                        """);
    }

    @Test
    void patch_explicitNullFundingJar_detachesIt() {
        UUID goalId = idOf(create("Xe máy", "40000000", NEXT_YEAR, jarId("LTSS")));

        assertThat(patch(goalId, "{\"fundingJarId\": null}"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"fundingJar": null, "progressPct": null}
                        """);
    }

    @Test
    void patch_archiving_mustUseDelete() {
        UUID goalId = idOf(create("Nhà", "1000000000", NEXT_YEAR, null));

        assertProblem(
                patch(goalId, "{\"status\": \"ARCHIVED\"}"), HttpStatus.UNPROCESSABLE_CONTENT, "use-delete-to-archive");
    }

    @Test
    void delete_archives_isIdempotent_andHidesTheGoal() {
        UUID goalId = idOf(create("Tạm", "1000", NEXT_YEAR, null));

        assertThat(delete(goalId)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(delete(goalId)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(list()).bodyJson().extractingPath("$.items.length()").isEqualTo(0);
        assertProblem(get(goalId), HttpStatus.NOT_FOUND, "goal-not-found");
        assertThat(jdbc.sql("SELECT status FROM goals WHERE id = ?")
                        .param(goalId)
                        .query(String.class)
                        .single())
                .isEqualTo("ARCHIVED"); // soft delete: the row is still there
    }

    @Test
    void goalOfAnotherUser_is404_forEveryMethod() {
        UUID goalId = idOf(create("Bí mật", "1000", NEXT_YEAR, null));

        userId = insertUser();
        assertProblem(get(goalId), HttpStatus.NOT_FOUND, "goal-not-found");
        assertProblem(patch(goalId, "{\"name\": \"hack\"}"), HttpStatus.NOT_FOUND, "goal-not-found");
        assertProblem(delete(goalId), HttpStatus.NOT_FOUND, "goal-not-found");
    }

    @Test
    void invalidBody_isAValidationError() {
        MvcTestResult result = create("", "1000.50", NEXT_YEAR, null);

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "validation-failed");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("name", "targetAmount");
    }

    // --- helpers ---

    private MvcTestResult create(String name, String amount, String targetDate, UUID fundingJarId) {
        String jar = fundingJarId == null ? "null" : "\"" + fundingJarId + "\"";
        return call(
                mvc.post()
                        .uri("/api/v1/goals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"name": "%s", "targetAmount": "%s", "targetDate": "%s", "fundingJarId": %s}
                        """.formatted(name, amount, targetDate, jar)),
                WRITE);
    }

    private MvcTestResult list() {
        return call(mvc.get().uri("/api/v1/goals"), READ);
    }

    private MvcTestResult get(UUID goalId) {
        return call(mvc.get().uri("/api/v1/goals/" + goalId), READ);
    }

    private MvcTestResult patch(UUID goalId, String json) {
        return call(
                mvc.patch()
                        .uri("/api/v1/goals/" + goalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json),
                WRITE);
    }

    private MvcTestResult delete(UUID goalId) {
        return call(mvc.delete().uri("/api/v1/goals/" + goalId), WRITE);
    }

    private MvcTestResult call(MockMvcTester.MockMvcRequestBuilder request, SimpleGrantedAuthority scope) {
        return request.with(jwt().authorities(scope)).exchange();
    }

    /** Money reaches a jar the normal way: income into POOL, then an allocation (ledger API, P1.5). */
    private void fundPlay(String amount) {
        call(
                mvc.post()
                        .uri("/api/v1/transactions")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"INCOME\", \"amount\": \"%s\"}".formatted(amount)),
                WRITE);
        call(
                mvc.post()
                        .uri("/api/v1/jars/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromJarId": "%s", "toJarId": "%s", "amount": "%s"}
                                """.formatted(jarId("POOL"), jarId("PLAY"), amount)),
                WRITE);
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result)
                .hasStatus(status)
                .bodyJson()
                .extractingPath("$.type")
                .isEqualTo("https://jars.local/problems/" + code);
    }

    private static UUID idOf(MvcTestResult created) {
        String location = created.getResponse().getHeader("Location");
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
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
}
