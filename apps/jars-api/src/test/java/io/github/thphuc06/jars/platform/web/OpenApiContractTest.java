package io.github.thphuc06.jars.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.identity.CurrentUser;
import io.github.thphuc06.jars.identity.IdentityApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
 * contracts/openapi/jars-api.yaml is the contract the web client is generated from. This test keeps it honest in
 * three ways: the committed file is exactly what the API serves, the documented security is what the filter chain
 * enforces, and a real response of EVERY operation fits the documented schema (no missing or undocumented property,
 * no null where the schema does not allow one).
 *
 * <p>After an intended API change: {@code ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true}, review the
 * diff of the YAML file and commit it with the change.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiContractTest {

    static final Path CONTRACT = Path.of("../../contracts/openapi/jars-api.yaml");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final SimpleGrantedAuthority READ = new SimpleGrantedAuthority("SCOPE_jars-api/read");
    static final SimpleGrantedAuthority WRITE = new SimpleGrantedAuthority("SCOPE_jars-api/write");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    IdentityApi identity;

    JsonNode spec;
    UUID userId;
    final Set<String> exercised = new TreeSet<>();

    @BeforeEach
    void setUp() throws Exception {
        spec = JSON.readTree(body(mvc.get().uri("/v3/api-docs").exchange()));
        userId = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, cognito_sub, email) VALUES (?, ?, ?)")
                .params(userId, "sub-" + userId, userId + "@example.com")
                .update();
        // displayName null on purpose: GET /me must then show a property the schema allows to be null
        given(identity.currentUser())
                .willAnswer(call -> new CurrentUser(userId, "u@example.com", null, "vi-VN", Instant.EPOCH));
    }

    @Test
    void committedContract_isExactlyWhatTheApiServes() throws Exception {
        String served = body(mvc.get().uri("/v3/api-docs.yaml").exchange());
        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(CONTRACT, served);
        }
        String committed = Files.exists(CONTRACT) ? Files.readString(CONTRACT).replace("\r\n", "\n") : "";
        if (!committed.equals(served)) {
            Path actual = Path.of("target/openapi/jars-api.yaml");
            Files.createDirectories(actual.getParent());
            Files.writeString(actual, served);
        }
        assertThat(committed)
                .as("contracts/openapi/jars-api.yaml is out of date (served version: target/openapi/jars-api.yaml). "
                        + "Run ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true and commit the diff")
                .isEqualTo(served);
    }

    @Test
    void operationIds_areTheMethodNames_neverGeneratedSuffixes() {
        List<String> ids = new ArrayList<>();
        operations().forEach(op -> ids.add(op.node().path("operationId").asString()));

        // springdoc appends _1, _2 when two handler methods share a name: unstable, and meaningless in a client
        assertThat(ids).doesNotHaveDuplicates().allSatisfy(id -> assertThat(id).doesNotContainPattern("_\\d+$"));
    }

    @Test
    void documentedSecurity_isWhatTheFilterChainEnforces() {
        for (Op op : operations()) {
            String uri = op.path().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            JsonNode security = op.node().path("security");
            int anonymous = status(op.method(), uri, null);
            if (security.isMissingNode()) {
                assertThat(anonymous).as(op + " is documented as public").isNotIn(401, 403);
                continue;
            }
            String scope = security.get(0).path(ApiContractRules.BEARER).get(0).asString();
            SimpleGrantedAuthority other = scope.equals(ApiContractRules.READ_SCOPE) ? WRITE : READ;
            assertThat(anonymous).as(op + " without a token").isEqualTo(401);
            assertThat(status(op.method(), uri, other))
                    .as(op + " with only " + other)
                    .isEqualTo(403);
        }
    }

    @Test
    void everyOperation_realResponseFitsItsSchema() throws Exception {
        check(HttpMethod.GET, "/api/v1/health", call(HttpMethod.GET, "/api/v1/health", null, null), 200);
        check(HttpMethod.GET, "/api/v1/me", call(HttpMethod.GET, "/api/v1/me", null, READ), 200);
        check(HttpMethod.GET, "/api/v1/jars", call(HttpMethod.GET, "/api/v1/jars", null, null), 401);
        JsonNode jars = check(HttpMethod.GET, "/api/v1/jars", call(HttpMethod.GET, "/api/v1/jars", null, READ), 200);
        String nec = jars.path("jars").get(0).path("id").asString();
        // the id a client allocates income from, read from the API like the web client does (not from the database)
        String pool = jars.path("poolJarId").asString();
        check(HttpMethod.GET, "/api/v1/categories", call(HttpMethod.GET, "/api/v1/categories", null, READ), 200);

        // money in, allocated, spent without a category: the responses carry every kind of null
        String income = """
                {"type": "INCOME", "amount": "500000"}""";
        check(
                HttpMethod.POST,
                "/api/v1/transactions",
                call(HttpMethod.POST, "/api/v1/transactions", income, WRITE),
                201);
        String transfer = """
                {"fromJarId": "%s", "toJarId": "%s", "amount": "200000"}""".formatted(pool, nec);
        check(
                HttpMethod.POST,
                "/api/v1/jars/transfers",
                call(HttpMethod.POST, "/api/v1/jars/transfers", transfer, WRITE),
                201);
        String expense = """
                {"type": "EXPENSE", "amount": "50000", "jarId": "%s"}""".formatted(nec);
        String spent = check(
                        HttpMethod.POST,
                        "/api/v1/transactions",
                        call(HttpMethod.POST, "/api/v1/transactions", expense, WRITE),
                        201)
                .path("transaction")
                .path("id")
                .asString();
        check(
                HttpMethod.POST,
                "/api/v1/transactions",
                call(HttpMethod.POST, "/api/v1/transactions", "{\"type\": \"INCOME\", \"amount\": \"0.5\"}", WRITE),
                422);
        check(HttpMethod.GET, "/api/v1/transactions", call(HttpMethod.GET, "/api/v1/transactions", null, READ), 200);
        check(
                HttpMethod.GET,
                "/api/v1/transactions/{id}",
                call(HttpMethod.GET, "/api/v1/transactions/" + spent, null, READ),
                200);
        check(
                HttpMethod.PATCH,
                "/api/v1/transactions/{id}",
                call(HttpMethod.PATCH, "/api/v1/transactions/" + spent, "{\"note\": \"ăn sáng\"}", WRITE),
                200);
        check(HttpMethod.GET, "/api/v1/dashboard", call(HttpMethod.GET, "/api/v1/dashboard", null, READ), 200);
        check(
                HttpMethod.GET,
                "/api/v1/balances/daily",
                call(HttpMethod.GET, "/api/v1/balances/daily?days=3", null, READ),
                200);

        String answers = """
                {"riskTolerance": "MODERATE", "horizon": "YEARS_2_TO_5", "liquidity": "FLEXIBLE",
                 "experience": "BEGINNER", "mainGoal": "EMERGENCY_FUND"}""";
        check(
                HttpMethod.PUT,
                "/api/v1/me/risk-profile",
                call(HttpMethod.PUT, "/api/v1/me/risk-profile", answers, WRITE),
                200);
        check(
                HttpMethod.GET,
                "/api/v1/me/risk-profile",
                call(HttpMethod.GET, "/api/v1/me/risk-profile", null, READ),
                200);

        String goal = """
                {"name": "Laptop", "targetAmount": "30000000", "targetDate": "%s"}""".formatted(LocalDate.now().plusYears(1));
        String goalId = check(
                        HttpMethod.POST, "/api/v1/goals", call(HttpMethod.POST, "/api/v1/goals", goal, WRITE), 201)
                .path("id")
                .asString();
        check(HttpMethod.GET, "/api/v1/goals/{id}", call(HttpMethod.GET, "/api/v1/goals/" + goalId, null, READ), 200);
        check(
                HttpMethod.PATCH,
                "/api/v1/goals/{id}",
                call(HttpMethod.PATCH, "/api/v1/goals/" + goalId, "{\"fundingJarId\": \"%s\"}".formatted(nec), WRITE),
                200);
        check(HttpMethod.GET, "/api/v1/goals", call(HttpMethod.GET, "/api/v1/goals", null, READ), 200);
        check(
                HttpMethod.DELETE,
                "/api/v1/goals/{id}",
                call(HttpMethod.DELETE, "/api/v1/goals/" + goalId, null, WRITE),
                204);

        Set<String> all = new TreeSet<>();
        operations().forEach(op -> all.add(op.node().path("operationId").asString()));
        assertThat(exercised)
                .as("every operation needs a real call here, so its schema is checked against a real response")
                .isEqualTo(all);
    }

    // --- calling the API ---

    private MvcTestResult call(HttpMethod method, String uri, String json, SimpleGrantedAuthority authority) {
        var request = mvc.method(method)
                .uri(uri)
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        if (authority != null) {
            request = request.with(jwt().authorities(authority));
        }
        return request.exchange();
    }

    private int status(String method, String uri, SimpleGrantedAuthority authority) {
        return call(HttpMethod.valueOf(method.toUpperCase()), uri, "{}", authority)
                .getResponse()
                .getStatus();
    }

    private static String body(MvcTestResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // --- reading the spec ---

    record Op(String method, String path, JsonNode node) {
        @Override
        public String toString() {
            return method.toUpperCase() + " " + path;
        }
    }

    private List<Op> operations() {
        List<Op> ops = new ArrayList<>();
        spec.path("paths")
                .properties()
                .forEach(path -> path.getValue()
                        .properties()
                        .forEach(op -> ops.add(new Op(op.getKey(), path.getKey(), op.getValue()))));
        return ops;
    }

    private JsonNode resolve(JsonNode node) {
        while (node.has("$ref")) {
            node = spec.at(node.get("$ref").asString().substring(1)); // "#/components/..." → JSON pointer
        }
        return node;
    }

    /** The response must have a documented status, and its body must fit the documented schema. */
    private JsonNode check(HttpMethod method, String path, MvcTestResult result, int expectedStatus) throws Exception {
        assertThat(result).hasStatus(expectedStatus);
        JsonNode operation = spec.path("paths").path(path).path(method.name().toLowerCase());
        assertThat(operation.isMissingNode())
                .as(method + " " + path + " is documented")
                .isFalse();
        exercised.add(operation.path("operationId").asString());

        JsonNode responses = operation.path("responses");
        JsonNode response = responses.path(String.valueOf(expectedStatus));
        if (response.isMissingNode() && expectedStatus >= 400) {
            response = responses.path("default");
        }
        assertThat(response.isMissingNode())
                .as(method + " " + path + " documents status " + expectedStatus)
                .isFalse();
        response = resolve(response);

        String text = body(result);
        if (!response.has("content")) {
            assertThat(text).as(method + " " + path + " has no body").isEmpty();
            return JSON.missingNode();
        }
        JsonNode content = response.get("content");
        String mediaType = content.propertyNames().iterator().next();
        assertThat(result.getResponse().getContentType()).startsWith(mediaType);
        JsonNode value = JSON.readTree(text);
        fits(value, content.get(mediaType).get("schema"), method + " " + path + " → " + expectedStatus);
        return value;
    }

    /** A small JSON Schema check: enough for the shapes springdoc produces (object, array, scalar, oneOf, null). */
    private void fits(JsonNode value, JsonNode declared, String where) {
        JsonNode schema = resolve(declared);
        if (schema.has("oneOf")) {
            List<String> failures = new ArrayList<>();
            for (JsonNode branch : schema.get("oneOf")) {
                try {
                    fits(value, branch, where);
                    return;
                } catch (AssertionError e) {
                    failures.add(e.getMessage());
                }
            }
            throw new AssertionError(where + " fits no oneOf branch: " + failures);
        }
        Set<String> types = new HashSet<>();
        JsonNode type = schema.path("type");
        if (type.isArray()) {
            type.forEach(t -> types.add(t.asString()));
        } else if (!type.isMissingNode()) {
            types.add(type.asString());
        }
        if (value.isNull()) {
            assertThat(types)
                    .as(where + " is null, so the schema must allow null")
                    .contains("null");
            return;
        }
        if (types.contains("object")) {
            Set<String> keys = new TreeSet<>(value.propertyNames());
            Set<String> properties = new TreeSet<>(schema.path("properties").propertyNames());
            Set<String> required = new TreeSet<>();
            schema.path("required").forEach(r -> required.add(r.asString()));
            assertThat(properties).as(where + ": every property is documented").containsAll(keys);
            assertThat(keys).as(where + ": every required property is present").containsAll(required);
            keys.forEach(key -> fits(value.get(key), schema.get("properties").get(key), where + "." + key));
        } else if (types.contains("array")) {
            assertThat(value.isArray()).as(where + " is an array").isTrue();
            value.forEach(item -> fits(item, schema.get("items"), where + "[]"));
        } else if (types.contains("string")) {
            assertThat(value.isString()).as(where + " is a string").isTrue();
            if (schema.has("enum")) {
                List<String> allowed = new ArrayList<>();
                schema.get("enum").forEach(e -> allowed.add(e.isNull() ? null : e.asString()));
                assertThat(allowed).as(where + " is one of the enum values").contains(value.asString());
            }
        } else if (types.contains("integer")) {
            assertThat(value.isIntegralNumber()).as(where + " is an integer").isTrue();
        } else if (types.contains("number")) {
            assertThat(value.isNumber()).as(where + " is a number").isTrue();
        } else if (types.contains("boolean")) {
            assertThat(value.isBoolean()).as(where + " is a boolean").isTrue();
        } else {
            throw new AssertionError(where + ": schema without a type the check knows: " + schema);
        }
    }
}
