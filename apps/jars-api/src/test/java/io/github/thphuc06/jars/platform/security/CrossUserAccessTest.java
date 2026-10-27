package io.github.thphuc06.jars.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.identity.CurrentUser;
import io.github.thphuc06.jars.identity.IdentityApi;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * IDOR (insecure direct object reference): user B uses ids that belong to user A. Every such request must answer
 * 404, exactly like an id that does not exist (403 would confirm that the id exists), and change nothing of A's.
 *
 * <p>The list of operations with an {id} comes from the OpenAPI document, so a new endpoint with a path id fails
 * this test until a cross-user case is added here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CrossUserAccessTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final SimpleGrantedAuthority READ = new SimpleGrantedAuthority("SCOPE_jars-api/read");
    static final SimpleGrantedAuthority WRITE = new SimpleGrantedAuthority("SCOPE_jars-api/write");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    IdentityApi identity;

    final AtomicReference<UUID> signedIn = new AtomicReference<>();
    UUID alice;
    UUID bob;

    // Alice's data, created through the API
    String aliceNec;
    String aliceTransaction;
    String aliceGoal;
    JsonNode aliceJarsBefore;

    @BeforeEach
    void twoUsers() throws Exception {
        given(identity.currentUser())
                .willAnswer(call -> new CurrentUser(signedIn.get(), "u@example.com", null, "vi-VN", Instant.EPOCH));
        alice = user();
        bob = user();

        as(alice);
        JsonNode jars = json(call(HttpMethod.GET, "/api/v1/jars", null, READ), 200);
        aliceNec = jars.path("jars").get(0).path("id").asString();
        String pool = jars.path("poolJarId").asString();
        status(
                call(HttpMethod.POST, "/api/v1/transactions", "{\"type\": \"INCOME\", \"amount\": \"1000000\"}", WRITE),
                201);
        status(
                call(
                        HttpMethod.POST,
                        "/api/v1/jars/transfers",
                        "{\"fromJarId\": \"%s\", \"toJarId\": \"%s\", \"amount\": \"400000\"}"
                                .formatted(pool, aliceNec),
                        WRITE),
                201);
        aliceTransaction = json(
                        call(
                                HttpMethod.POST,
                                "/api/v1/transactions",
                                "{\"type\": \"EXPENSE\", \"amount\": \"50000\", \"jarId\": \"%s\", \"note\": \"mine\"}"
                                        .formatted(aliceNec),
                                WRITE),
                        201)
                .path("transaction")
                .path("id")
                .asString();
        aliceGoal = json(
                        call(
                                HttpMethod.POST,
                                "/api/v1/goals",
                                "{\"name\": \"Laptop\", \"targetAmount\": \"30000000\", \"targetDate\": \"%s\", \"fundingJarId\": \"%s\"}"
                                        .formatted(LocalDate.now().plusYears(1), aliceNec),
                                WRITE),
                        201)
                .path("id")
                .asString();
        aliceJarsBefore = json(call(HttpMethod.GET, "/api/v1/jars", null, READ), 200);

        as(bob);
        status(call(HttpMethod.GET, "/api/v1/jars", null, READ), 200); // Bob has jars of his own
    }

    @Test
    void everyPathId_ofAnotherUser_isNotFound() throws Exception {
        as(bob);
        Map<String, MvcTestResult> attempts = new LinkedHashMap<>();
        attempts.put(
                "GET /api/v1/transactions/{id}",
                call(HttpMethod.GET, "/api/v1/transactions/" + aliceTransaction, null, READ));
        attempts.put(
                "PATCH /api/v1/transactions/{id}",
                call(
                        HttpMethod.PATCH,
                        "/api/v1/transactions/" + aliceTransaction,
                        "{\"note\": \"bob was here\"}",
                        WRITE));
        attempts.put("GET /api/v1/goals/{id}", call(HttpMethod.GET, "/api/v1/goals/" + aliceGoal, null, READ));
        attempts.put(
                "PATCH /api/v1/goals/{id}",
                call(HttpMethod.PATCH, "/api/v1/goals/" + aliceGoal, "{\"name\": \"mine\"}", WRITE));
        attempts.put("DELETE /api/v1/goals/{id}", call(HttpMethod.DELETE, "/api/v1/goals/" + aliceGoal, null, WRITE));

        for (Map.Entry<String, MvcTestResult> attempt : attempts.entrySet()) {
            String operation = attempt.getKey();
            MvcTestResult result = attempt.getValue();
            assertThat(result).as(operation).hasStatus(404);
            assertThat(body(result))
                    .as(operation + " says nothing about Alice's data")
                    .doesNotContain("mine", "Laptop");
        }
        // the same answer as for an id that never existed: nothing to tell the two apart
        assertThat(call(HttpMethod.GET, "/api/v1/transactions/" + UUID.randomUUID(), null, READ))
                .hasStatus(404);

        assertThat(attempts.keySet())
                .as("every operation with a path id has a cross-user case here (add one for a new endpoint)")
                .containsExactlyInAnyOrderElementsOf(operationsWithPathId());
        assertAliceUnchanged();
    }

    @Test
    void idsOfAnotherUser_inBodiesAndFilters_areNotFound_orFindNothing() throws Exception {
        as(bob);
        String bobNec = json(call(HttpMethod.GET, "/api/v1/jars", null, READ), 200)
                .path("jars")
                .get(0)
                .path("id")
                .asString();
        String bobPool = json(call(HttpMethod.GET, "/api/v1/jars", null, READ), 200)
                .path("poolJarId")
                .asString();
        String bobIncome = json(
                        call(
                                HttpMethod.POST,
                                "/api/v1/transactions",
                                "{\"type\": \"INCOME\", \"amount\": \"100000\"}",
                                WRITE),
                        201)
                .path("transaction")
                .path("id")
                .asString();
        String bobTransfer = json(
                        call(
                                HttpMethod.POST,
                                "/api/v1/jars/transfers",
                                "{\"fromJarId\": \"%s\", \"toJarId\": \"%s\", \"amount\": \"10000\"}"
                                        .formatted(bobPool, bobNec),
                                WRITE),
                        201)
                .path("transaction")
                .path("id")
                .asString();

        // spending from, moving into or out of, and saving with Alice's jar
        assertThat(call(
                        HttpMethod.POST,
                        "/api/v1/transactions",
                        "{\"type\": \"EXPENSE\", \"amount\": \"1000\", \"jarId\": \"%s\"}".formatted(aliceNec),
                        WRITE))
                .hasStatus(404);
        assertThat(call(
                        HttpMethod.POST,
                        "/api/v1/jars/transfers",
                        "{\"fromJarId\": \"%s\", \"toJarId\": \"%s\", \"amount\": \"1000\"}"
                                .formatted(aliceNec, bobNec),
                        WRITE))
                .hasStatus(404);
        assertThat(call(
                        HttpMethod.POST,
                        "/api/v1/jars/transfers",
                        "{\"fromJarId\": \"%s\", \"toJarId\": \"%s\", \"amount\": \"1000\"}"
                                .formatted(bobPool, aliceNec),
                        WRITE))
                .hasStatus(404);
        assertThat(call(
                        HttpMethod.POST,
                        "/api/v1/goals",
                        "{\"name\": \"x\", \"targetAmount\": \"1000\", \"targetDate\": \"%s\", \"fundingJarId\": \"%s\"}"
                                .formatted(LocalDate.now().plusYears(1), aliceNec),
                        WRITE))
                .hasStatus(404);
        // moving his OWN income into Alice's jar
        assertThat(call(
                        HttpMethod.PATCH,
                        "/api/v1/transactions/" + bobIncome,
                        "{\"jarId\": \"%s\"}".formatted(aliceNec),
                        WRITE))
                .hasStatus(404);
        // the jars of a transfer never change: that rule answers first, and it says nothing about Alice's jar
        assertThat(call(
                        HttpMethod.PATCH,
                        "/api/v1/transactions/" + bobTransfer,
                        "{\"jarId\": \"%s\"}".formatted(aliceNec),
                        WRITE))
                .hasStatus(422)
                .bodyJson()
                .extractingPath("$.type")
                .asString()
                .endsWith("/transfer-jars-immutable");

        // a filter with Alice's jar is applied together with "owned by the caller": an empty page, not her rows
        JsonNode filtered = json(call(HttpMethod.GET, "/api/v1/transactions?jarId=" + aliceNec, null, READ), 200);
        assertThat(filtered.path("items").isEmpty()).isTrue();

        assertAliceUnchanged();
    }

    // --- helpers ---

    private void assertAliceUnchanged() throws Exception {
        as(alice);
        assertThat(json(call(HttpMethod.GET, "/api/v1/jars", null, READ), 200)).isEqualTo(aliceJarsBefore);
        assertThat(json(call(HttpMethod.GET, "/api/v1/transactions/" + aliceTransaction, null, READ), 200)
                        .path("note")
                        .asString())
                .isEqualTo("mine");
        JsonNode goal = json(call(HttpMethod.GET, "/api/v1/goals/" + aliceGoal, null, READ), 200);
        assertThat(goal.path("name").asString()).isEqualTo("Laptop");
        assertThat(goal.path("status").asString()).isEqualTo("ACTIVE");
        as(bob);
    }

    private Set<String> operationsWithPathId() throws Exception {
        JsonNode spec = JSON.readTree(body(mvc.get().uri("/v3/api-docs").exchange()));
        Set<String> operations = new TreeSet<>();
        spec.path("paths").properties().forEach(path -> {
            if (path.getKey().contains("{")) {
                path.getValue()
                        .propertyNames()
                        .forEach(method -> operations.add(method.toUpperCase() + " " + path.getKey()));
            }
        });
        return operations;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(id, "sub-" + id, id + "@example.com")
                .update();
        return id;
    }

    private void as(UUID user) {
        signedIn.set(user);
    }

    private MvcTestResult call(HttpMethod method, String uri, String json, SimpleGrantedAuthority authority) {
        var request = mvc.method(method)
                .uri(uri)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(jwt().authorities(authority));
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return request.exchange();
    }

    private static void status(MvcTestResult result, int expected) {
        assertThat(result).hasStatus(expected);
    }

    private static JsonNode json(MvcTestResult result, int expected) throws Exception {
        assertThat(result).hasStatus(expected);
        return JSON.readTree(body(result));
    }

    private static String body(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
