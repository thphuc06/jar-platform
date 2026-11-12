package io.github.thphuc06.jars;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared plumbing for API tests that run the whole application against a real PostgreSQL. Each test uses fresh users
 * (a random Cognito subject), so tests never see each other's data and can share one application context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class ApiTestSupport {

    // ids of system categories seeded by V4__ledger.sql
    protected static final UUID CATEGORY_FOOD = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    protected static final UUID CATEGORY_TRANSPORT = UUID.fromString("018f0000-0000-7000-8000-000000000003");
    protected static final UUID CATEGORY_SALARY = UUID.fromString("018f0000-0000-7000-8000-000000000101");

    /** A response reduced to what assertions need. */
    public record Resp(int status, String body, MockHttpServletResponse raw, JsonMapper mapper) {
        public JsonNode json() {
            return mapper.readTree(body);
        }

        public String header(String name) {
            return raw.getHeader(name);
        }
    }

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected JsonMapper mapper;

    @Autowired
    protected JdbcClient jdbc;

    protected static JwtRequestPostProcessor newUser() {
        String sub = "test-" + UUID.randomUUID();
        return jwt().jwt(j -> j.subject(sub));
    }

    protected Resp call(
            HttpMethod method, String uri, JwtRequestPostProcessor token, String idempotencyKey, String body) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, uri);
        if (token != null) {
            request.with(token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MockHttpServletResponse response = mvc.perform(request).getResponse();
        return new Resp(
                response.getStatus(),
                new String(response.getContentAsByteArray(), StandardCharsets.UTF_8),
                response,
                mapper);
    }

    protected Resp get(JwtRequestPostProcessor token, String uri) {
        return call(HttpMethod.GET, uri, token, null, null);
    }

    protected Resp post(JwtRequestPostProcessor token, String uri, String key, String body) {
        return call(HttpMethod.POST, uri, token, key, body);
    }

    protected Resp patch(JwtRequestPostProcessor token, String uri, String body) {
        return call(HttpMethod.PATCH, uri, token, null, body);
    }

    protected Resp delete(JwtRequestPostProcessor token, String uri) {
        return call(HttpMethod.DELETE, uri, token, null, null);
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    /** Id of one of the user's jars by code (NEC, FFA, LTSS, EDU, PLAY, GIVE). Creates the user on first use. */
    protected UUID jarId(JwtRequestPostProcessor token, String code) {
        for (JsonNode jar : get(token, "/api/v1/jars").json()) {
            if (jar.get("code").asString().equals(code)) {
                return UUID.fromString(jar.get("id").asString());
            }
        }
        throw new AssertionError("no jar with code " + code);
    }

    protected Resp spend(JwtRequestPostProcessor token, UUID jarId, String amount) {
        return post(token, "/api/v1/transactions", newKey(), expenseBody(jarId, amount, CATEGORY_FOOD, null));
    }

    protected static String expenseBody(UUID jarId, String amount, UUID categoryId, String occurredAt) {
        return "{\"type\":\"EXPENSE\",\"amount\":" + amount + ",\"jarId\":\"" + jarId + "\",\"categoryId\":\""
                + categoryId + "\"" + (occurredAt == null ? "" : ",\"occurredAt\":\"" + occurredAt + "\"") + "}";
    }

    protected static String incomeBody(UUID jarId, String amount) {
        return "{\"type\":\"INCOME\",\"amount\":" + amount + (jarId == null ? "" : ",\"jarId\":\"" + jarId + "\"")
                + ",\"categoryId\":\"" + CATEGORY_SALARY + "\"}";
    }

    protected static String transferBody(UUID from, UUID to, String amount) {
        return "{\"fromJarId\":\"" + from + "\",\"toJarId\":\"" + to + "\",\"amount\":" + amount + "}";
    }

    protected BigDecimal balanceOf(UUID jarId) {
        return jdbc.sql("SELECT balance FROM jars WHERE id = ?")
                .param(jarId)
                .query(BigDecimal.class)
                .single();
    }

    protected BigDecimal entriesSum(UUID jarId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM jar_entries WHERE jar_id = ?")
                .param(jarId)
                .query(BigDecimal.class)
                .single();
    }

    protected int countTransactions(UUID userIdOfJar) {
        return jdbc.sql("SELECT count(*) FROM transactions t JOIN jars j ON j.user_id = t.user_id WHERE j.id = ?")
                .param(userIdOfJar)
                .query(Integer.class)
                .single();
    }

    /** The ledger invariant of ADR-0014: a jar's cached balance is exactly the sum of its entries. */
    protected void assertBalanceMatchesEntries(UUID jarId) {
        BigDecimal balance = balanceOf(jarId);
        BigDecimal sum = entriesSum(jarId);
        if (balance.compareTo(sum) != 0) {
            throw new AssertionError("jar " + jarId + " balance " + balance + " != sum of entries " + sum);
        }
    }
}
