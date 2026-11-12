package io.github.thphuc06.jars.goals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.thphuc06.jars.ApiTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import tools.jackson.databind.JsonNode;

/** Goals end to end: CRUD, ownership, feasibility, what-if scenarios and the roadmap. */
class GoalsApiTest extends ApiTestSupport {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private static LocalDate inMonths(int months) {
        return LocalDate.now(VIETNAM).plusMonths(months);
    }

    private static String goalBody(String name, String target, LocalDate date, UUID jar) {
        return "{\"name\":\"" + name + "\",\"targetAmount\":" + target + ",\"targetDate\":\"" + date + "\""
                + (jar == null ? "" : ",\"fundingJarId\":\"" + jar + "\"") + "}";
    }

    private JsonNode createGoal(JwtRequestPostProcessor user, String name, String target, int months, UUID jar) {
        Resp created = post(user, "/api/v1/goals", null, goalBody(name, target, inMonths(months), jar));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return created.json();
    }

    /** A user with 15M income and 5M spending this month, 10M sitting in the NEC jar; waits for the read model. */
    private UUID userWithHistory(JwtRequestPostProcessor user) {
        UUID nec = jarId(user, "NEC");
        post(user, "/api/v1/transactions", newKey(), incomeBody(nec, "15000000"));
        post(user, "/api/v1/transactions", newKey(), expenseBody(nec, "5000000", CATEGORY_FOOD, null));
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    JsonNode d = get(user, "/api/v1/dashboard").json();
                    return "5000000.00".equals(d.get("expense").get("amount").asString());
                });
        return nec;
    }

    // ------------------------------------------------------------------ CRUD

    @Test
    void createListUpdateAndArchive_aGoal() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = jarId(user, "NEC");
        assertThat(get(user, "/api/v1/goals").json()).isEmpty();

        JsonNode goal = createGoal(user, "Laptop", "25000000", 8, nec);

        String id = goal.get("id").asString();
        assertThat(goal.get("name").asString()).isEqualTo("Laptop");
        assertThat(goal.get("targetAmount").get("amount").asString()).isEqualTo("25000000.00");
        assertThat(goal.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(goal.get("fundingJarId").asString()).isEqualTo(nec.toString());
        assertThat(get(user, "/api/v1/goals").json()).hasSize(1);

        Resp updated = patch(user, "/api/v1/goals/" + id, "{\"name\":\"  Laptop mới \",\"targetAmount\":30000000}");
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("name").asString()).isEqualTo("Laptop mới");
        assertThat(updated.json().get("targetAmount").get("amount").asString()).isEqualTo("30000000.00");
        assertThat(updated.json().get("fundingJarId").asString())
                .as("untouched")
                .isEqualTo(nec.toString());

        assertThat(patch(user, "/api/v1/goals/" + id, "{\"status\":\"ACHIEVED\"}")
                        .json()
                        .get("status")
                        .asString())
                .isEqualTo("ACHIEVED");
        assertThat(patch(user, "/api/v1/goals/" + id, "{\"status\":\"ACTIVE\"}")
                        .json()
                        .get("status")
                        .asString())
                .isEqualTo("ACTIVE");
        assertThat(patch(user, "/api/v1/goals/" + id, "{\"status\":\"ARCHIVED\"}")
                        .status())
                .as("archiving goes through DELETE")
                .isEqualTo(422);

        assertThat(delete(user, "/api/v1/goals/" + id).status()).isEqualTo(204);
        assertThat(get(user, "/api/v1/goals").json()).isEmpty();
        assertThat(get(user, "/api/v1/goals/" + id + "/feasibility").status())
                .as("archived = gone")
                .isEqualTo(404);
        assertThat(delete(user, "/api/v1/goals/" + id).status()).isEqualTo(404);
    }

    @Test
    void invalidGoals_areRejected() {
        JwtRequestPostProcessor user = newUser();

        assertThat(post(user, "/api/v1/goals", null, goalBody("", "1000000", inMonths(3), null))
                        .status())
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/goals", null, goalBody("x".repeat(81), "1000000", inMonths(3), null))
                        .status())
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/goals", null, goalBody("Xe", "0", inMonths(3), null))
                        .status())
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/goals", null, goalBody("Xe", "-5", inMonths(3), null))
                        .status())
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/goals", null, goalBody("Xe", "1000.123", inMonths(3), null))
                        .status())
                .as("three decimals")
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/goals", null, goalBody("Xe", "1000000", LocalDate.now(VIETNAM), null))
                        .status())
                .as("the date must be after today")
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/goals", null, goalBody("Xe", "1000000", inMonths(-1), null))
                        .status())
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/goals", null, "{\"name\":\"Xe\",\"targetAmount\":1000}")
                        .status())
                .as("missing date")
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/goals", null, goalBody("Xe", "1000000", inMonths(3), UUID.randomUUID()))
                        .status())
                .as("unknown funding jar")
                .isEqualTo(404);
        assertThat(get(user, "/api/v1/goals").json()).isEmpty();
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void anotherUser_cannotSeeOrTouchMyGoal_everythingIs404() {
        JwtRequestPostProcessor alice = newUser();
        JwtRequestPostProcessor bob = newUser();
        UUID aliceNec = jarId(alice, "NEC");
        UUID bobNec = jarId(bob, "NEC");
        String id = createGoal(alice, "Quỹ khẩn cấp", "20000000", 10, aliceNec)
                .get("id")
                .asString();

        assertThat(patch(bob, "/api/v1/goals/" + id, "{\"name\":\"hacked\"}").status())
                .as("update")
                .isEqualTo(404);
        assertThat(delete(bob, "/api/v1/goals/" + id).status()).as("archive").isEqualTo(404);
        assertThat(get(bob, "/api/v1/goals/" + id + "/feasibility").status())
                .as("feasibility")
                .isEqualTo(404);
        assertThat(post(bob, "/api/v1/goals/" + id + "/scenarios", null, "{}").status())
                .as("scenarios")
                .isEqualTo(404);
        assertThat(get(bob, "/api/v1/goals/" + id + "/roadmap").status())
                .as("roadmap")
                .isEqualTo(404);
        assertThat(get(bob, "/api/v1/goals").json()).as("list").isEmpty();
        assertThat(post(bob, "/api/v1/goals", null, goalBody("Của tôi", "1000000", inMonths(3), aliceNec))
                        .status())
                .as("fund my goal from her jar")
                .isEqualTo(404);
        assertThat(patch(
                                bob,
                                "/api/v1/goals/"
                                        + createGoal(bob, "Của Bob", "1000000", 3, bobNec)
                                                .get("id")
                                                .asString(),
                                "{\"fundingJarId\":\"" + aliceNec + "\"}")
                        .status())
                .as("re-fund with her jar")
                .isEqualTo(404);
        assertThat(get(alice, "/api/v1/goals").json()).as("hers is untouched").hasSize(1);
        assertThat(get(alice, "/api/v1/goals").json().get(0).get("name").asString())
                .isEqualTo("Quỹ khẩn cấp");
    }

    @Test
    void everyGoalEndpoint_requiresAToken() {
        UUID id = UUID.randomUUID();

        assertThat(get(null, "/api/v1/goals").status()).isEqualTo(401);
        assertThat(post(null, "/api/v1/goals", null, "{}").status()).isEqualTo(401);
        assertThat(patch(null, "/api/v1/goals/" + id, "{}").status()).isEqualTo(401);
        assertThat(delete(null, "/api/v1/goals/" + id).status()).isEqualTo(401);
        assertThat(get(null, "/api/v1/goals/" + id + "/feasibility").status()).isEqualTo(401);
        assertThat(post(null, "/api/v1/goals/" + id + "/scenarios", null, "{}").status())
                .isEqualTo(401);
        assertThat(get(null, "/api/v1/goals/" + id + "/roadmap").status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ feasibility

    @Test
    void feasibility_reflectsTheFundingJarAndThePace() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = userWithHistory(user);
        String id = createGoal(user, "Laptop", "25000000", 8, nec).get("id").asString();

        Resp response = get(user, "/api/v1/goals/" + id + "/feasibility");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode f = response.json();
        assertThat(f.get("goalId").asString()).isEqualTo(id);
        assertThat(f.get("fundingJarLinked").asBoolean()).isTrue();
        assertThat(f.get("currentAmount").get("amount").asString())
                .as("balance of the funding jar")
                .isEqualTo("10000000.00");
        assertThat(f.get("remaining").get("amount").asString()).isEqualTo("15000000.00");
        assertThat(f.get("monthsLeft").asInt()).isBetween(7, 8);
        BigDecimal requiredMonthly =
                new BigDecimal(f.get("requiredMonthly").get("amount").asString());
        assertThat(requiredMonthly).isBetween(new BigDecimal("1800000"), new BigDecimal("2200000"));
        assertThat(f.get("pace").get("averageIncome").get("amount").asString()).isEqualTo("15000000.00");
        assertThat(f.get("pace").get("averageExpense").get("amount").asString()).isEqualTo("5000000.00");
        assertThat(f.get("verdict").asString()).isEqualTo("ON_TRACK");
        assertThat(f.get("probabilityOfSuccess").decimalValue()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(f.get("grade").asString()).isNotBlank();
    }

    @Test
    void feasibility_ofAnOutOfReachGoal_isOffTrackWithAShortfall() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = userWithHistory(user);
        String id = createGoal(user, "Mua nhà", "5000000000", 6, nec).get("id").asString();

        JsonNode f = get(user, "/api/v1/goals/" + id + "/feasibility").json();

        assertThat(f.get("verdict").asString()).isEqualTo("OFF_TRACK");
        assertThat(new BigDecimal(f.get("monthlyShortfall").get("amount").asString()))
                .isPositive();
        assertThat(f.get("probabilityOfSuccess").decimalValue()).isLessThan(new BigDecimal("0.1"));
    }

    @Test
    void feasibility_isDeterministic_sameGoalSameAnswer() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = userWithHistory(user);
        String id = createGoal(user, "Du lịch", "40000000", 12, nec).get("id").asString();

        String first = get(user, "/api/v1/goals/" + id + "/feasibility").body();
        String second = get(user, "/api/v1/goals/" + id + "/feasibility").body();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void feasibility_withoutAnyHistory_stillAnswersAndSaysWhy() {
        JwtRequestPostProcessor user = newUser();
        String id = createGoal(user, "Xe máy", "30000000", 12, null).get("id").asString();

        Resp response = get(user, "/api/v1/goals/" + id + "/feasibility");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().get("fundingJarLinked").asBoolean()).isFalse();
        assertThat(response.json().get("notes")).as("explains what is missing").isNotEmpty();
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    void scenarios_withoutParameters_compareTheBaseCaseWithDefaultWhatIfs() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = userWithHistory(user);
        String id = createGoal(user, "Laptop", "25000000", 8, nec).get("id").asString();

        Resp response = post(user, "/api/v1/goals/" + id + "/scenarios", null, "{}");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode s = response.json();
        assertThat(s.get("base").get("monteCarlo").get("path"))
                .as("month-by-month p10/p50/p90, Monte Carlo only")
                .isNotEmpty();
        assertThat(s.get("base").get("deterministic").get("path"))
                .as("the other strategies answer with the end value only")
                .isEmpty();
        assertThat(s.get("scenarios").size()).isGreaterThanOrEqualTo(2);
        assertThat(s.get("seed").isNumber()).isTrue();
        assertThat(s.get("startingAmount").get("amount").asString()).isEqualTo("10000000.00");
    }

    @Test
    void scenarios_extraSavingImprovesTheOutcome_andIsReproducibleBySeed() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = userWithHistory(user);
        String id = createGoal(user, "Laptop", "60000000", 10, nec).get("id").asString();
        String body = "{\"monthlySavingDelta\":2000000,\"seed\":12345}";

        Resp first = post(user, "/api/v1/goals/" + id + "/scenarios", null, body);
        Resp again = post(user, "/api/v1/goals/" + id + "/scenarios", null, body);
        Resp otherSeed =
                post(user, "/api/v1/goals/" + id + "/scenarios", null, "{\"monthlySavingDelta\":2000000,\"seed\":999}");

        assertThat(first.status()).as(first.body()).isEqualTo(200);
        assertThat(again.body()).as("same seed, same answer").isEqualTo(first.body());
        assertThat(first.json().get("seed").asLong()).isEqualTo(12345L);
        assertThat(otherSeed.json().get("seed").asLong()).isEqualTo(999L);
        assertThat(otherSeed.body()).as("the seed drives the Monte Carlo draws").isNotEqualTo(first.body());
        JsonNode scenario = first.json().get("scenarios").get(0);
        assertThat(new BigDecimal(scenario.get("medianVsBase").get("amount").asString()))
                .as("saving 2M more per month must raise the median outcome")
                .isPositive();
    }

    @Test
    void scenarios_validateTheirParameters() {
        JwtRequestPostProcessor user = newUser();
        String id = createGoal(user, "Xe", "30000000", 12, null).get("id").asString();

        assertThat(post(user, "/api/v1/goals/" + id + "/scenarios", null, "{\"horizonMonths\":0}")
                        .status())
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/goals/" + id + "/scenarios", null, "{\"horizonMonths\":121}")
                        .status())
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/goals/" + id + "/scenarios", null, "{\"monthlySavingDelta\":1.234}")
                        .status())
                .isEqualTo(422);
        assertThat(post(user, "/api/v1/goals/" + id + "/scenarios", null, "{\"horizonMonths\":24}")
                        .status())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ roadmap

    @Test
    void roadmap_hasTheShapeTheScreensConsume() {
        JwtRequestPostProcessor user = newUser();
        UUID nec = userWithHistory(user);
        String id = createGoal(user, "Laptop", "25000000", 8, nec).get("id").asString();

        Resp response = get(user, "/api/v1/goals/" + id + "/roadmap");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode r = response.json();
        assertThat(r.get("schemaVersion").asString()).isNotBlank();
        assertThat(r.get("status").asString()).isNotBlank();
        assertThat(r.get("result").get("summary").asString()).isNotBlank();
        JsonNode contract = r.get("roadmapContract");
        assertThat(contract.get("goal").get("targetAmount").asLong())
                .as("plain number for the charts")
                .isEqualTo(25_000_000L);
        assertThat(contract.get("phases")).isNotEmpty();
        assertThat(contract.get("projection")).isNotNull();
        assertThat(contract.get("nextBestAction")).isNotNull();
        assertThat(r.get("uiExplanation")).isNotNull();
    }

    @Test
    void roadmap_withoutHistory_isNotReadyButNotAnError() {
        JwtRequestPostProcessor user = newUser();
        String id = createGoal(user, "Xe máy", "30000000", 12, null).get("id").asString();

        Resp response = get(user, "/api/v1/goals/" + id + "/roadmap");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().get("roadmapContract").get("status").asString())
                .isNotEqualTo("ready");
    }
}
