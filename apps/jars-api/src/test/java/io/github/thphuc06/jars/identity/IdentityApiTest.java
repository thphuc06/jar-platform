package io.github.thphuc06.jars.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** /api/v1/me end to end against a real PostgreSQL: lazy provisioning, profile edits and the risk questionnaire. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IdentityApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void firstRequest_createsTheUserAndLaterRequestsReturnTheSameOne() throws Exception {
        JwtRequestPostProcessor token = newUserToken();

        String first = getMe(token);
        String second = getMe(token);

        assertThat(first).contains("\"locale\":\"vi-VN\"").contains("\"onboardingCompleted\":false");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void manySimultaneousFirstRequests_createExactlyOneUser() throws Exception {
        String sub = "race-" + UUID.randomUUID();
        int threads = 12;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return getMe(jwt().jwt(j -> j.subject(sub)));
                }));
            }
            start.countDown();
            List<String> bodies = new ArrayList<>();
            for (Future<String> f : futures) {
                bodies.add(f.get());
            }

            assertThat(count("SELECT count(*) FROM users WHERE cognito_sub = ?", sub))
                    .isEqualTo(1);
            assertThat(bodies).allSatisfy(b -> assertThat(b).contains("\"id\":"));
            assertThat(bodies).containsOnly(bodies.getFirst()); // everyone saw the same id
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void patch_changesOnlyTheGivenFields() {
        JwtRequestPostProcessor token = newUserToken();

        var result = mvc.patch()
                .uri("/api/v1/me")
                .with(token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"  An Nguyen \"}");

        assertThat(result)
                .hasStatusOk()
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body)
                        .contains("\"displayName\":\"An Nguyen\"")
                        .contains("\"locale\":\"vi-VN\"")); // untouched
    }

    @Test
    void patch_rejectsAnUnsupportedLocale() {
        assertThat(mvc.patch()
                        .uri("/api/v1/me")
                        .with(newUserToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"fr-FR\"}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body).contains("\"field\":\"locale\""));
    }

    @Test
    void riskProfile_isNotFoundUntilTheQuestionnaireIsSubmitted() {
        assertThat(mvc.get().uri("/api/v1/me/risk-profile").with(newUserToken()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void submittingTheQuestionnaire_scoresItAndMarksOnboardingDone() {
        JwtRequestPostProcessor token = newUserToken();

        var put = mvc.put()
                .uri("/api/v1/me/risk-profile")
                .with(token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\":[2,3,2,2,1]}");
        assertThat(put)
                .hasStatusOk()
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body).contains("\"score\":100").contains("\"riskLevel\":\"GROWTH\""));

        assertThat(mvc.get().uri("/api/v1/me/risk-profile").with(token))
                .hasStatusOk()
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body).contains("\"riskLevel\":\"GROWTH\""));
        assertThat(mvc.get().uri("/api/v1/me").with(token))
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body).contains("\"onboardingCompleted\":true"));
    }

    @Test
    void resubmitting_keepsHistoryAndTheLatestAnswerWins() throws Exception {
        JwtRequestPostProcessor token = newUserToken();
        submit(token, "[2,3,2,2,1]");

        submit(token, "[0,0,0,0,0]");

        assertThat(mvc.get().uri("/api/v1/me/risk-profile").with(token))
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body).contains("\"riskLevel\":\"CONSERVATIVE\""));
        UUID userId = userIdFor(token);
        assertThat(count("SELECT count(*) FROM risk_assessments WHERE user_id = ?", userId))
                .isEqualTo(2);
    }

    @Test
    void invalidAnswers_are422() {
        assertThat(mvc.put()
                        .uri("/api/v1/me/risk-profile")
                        .with(newUserToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[0,9,0,0,0]}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body).contains("Câu 2"));
        assertThat(mvc.put()
                        .uri("/api/v1/me/risk-profile")
                        .with(newUserToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[0,0]}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void anUserWhoHasNotAnsweredTheTwoProfileQuestions_hasNoAgeBandAndNoIncomeSituation() throws Exception {
        JwtRequestPostProcessor token = newUserToken();
        submit(token, "[2,3,2,2,1]"); // a user who answered the questionnaire before the new questions existed

        String me = getMe(token);

        assertThat(me)
                .contains("\"onboardingCompleted\":true")
                .contains("\"ageBand\":null")
                .contains("\"incomeSituation\":null");
    }

    @Test
    void patch_setsTheAgeBandAndTheIncomeSituation_andLeavesTheOtherFieldsAlone() {
        JwtRequestPostProcessor token = newUserToken();

        var result = mvc.patch()
                .uri("/api/v1/me")
                .with(token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ageBand\":\"AGE_25_34\",\"incomeSituation\":\"IRREGULAR_INCOME\"}");
        assertThat(result)
                .hasStatusOk()
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body)
                        .contains("\"ageBand\":\"AGE_25_34\"")
                        .contains("\"incomeSituation\":\"IRREGULAR_INCOME\"")
                        .contains("\"locale\":\"vi-VN\""));

        // one of them alone changes only that one
        mvc.patch()
                .uri("/api/v1/me")
                .with(token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ageBand\":\"AGE_55_PLUS\"}")
                .exchange();
        assertThat(mvc.get().uri("/api/v1/me").with(token))
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body)
                        .contains("\"ageBand\":\"AGE_55_PLUS\"")
                        .contains("\"incomeSituation\":\"IRREGULAR_INCOME\""));
    }

    @Test
    void patch_rejectsAnAgeBandOrIncomeSituationThatIsNotOneOfTheCodes_inVietnamese() {
        assertThat(mvc.patch()
                        .uri("/api/v1/me")
                        .with(newUserToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ageBand\":\"1990-01-01\"}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body ->
                        assertThat(body).contains("\"field\":\"ageBand\"").contains("Nhóm tuổi phải là"));
        assertThat(mvc.patch()
                        .uri("/api/v1/me")
                        .with(newUserToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incomeSituation\":\"RICH\"}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .convertTo(String.class)
                .satisfies(body -> assertThat(body)
                        .contains("\"field\":\"incomeSituation\"")
                        .contains("Tình hình thu nhập phải là"));
    }

    @Test
    void changingTheAgeBandAndTheIncomeSituation_isAuditedAsCodes() throws Exception {
        JwtRequestPostProcessor token = newUserToken();
        UUID userId = userIdFor(token);

        mvc.patch()
                .uri("/api/v1/me")
                .with(token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ageBand\":\"UNDER_25\",\"incomeSituation\":\"NO_INCOME_YET\"}")
                .exchange();

        var payload = jdbc.sql("SELECT payload::text FROM audit_log WHERE actor = ? AND action = 'PROFILE_UPDATED'")
                .param(userId.toString())
                .query(String.class)
                .single();
        assertThat(payload).contains("\"ageBand\": \"UNDER_25\"").contains("\"incomeSituation\": \"NO_INCOME_YET\"");
    }

    @Test
    void usersNeverSeeEachOthersRiskProfile() {
        JwtRequestPostProcessor alice = newUserToken();
        JwtRequestPostProcessor bob = newUserToken();
        submit(alice, "[2,3,2,2,1]");

        assertThat(mvc.get().uri("/api/v1/me/risk-profile").with(bob)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void withoutAToken_meIsUnauthorized() {
        assertThat(mvc.get().uri("/api/v1/me")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private String getMe(JwtRequestPostProcessor token) throws java.io.IOException {
        byte[] body =
                mvc.get().uri("/api/v1/me").with(token).exchange().getResponse().getContentAsByteArray();
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void submit(JwtRequestPostProcessor token, String answersJson) {
        assertThat(mvc.put()
                        .uri("/api/v1/me/risk-profile")
                        .with(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":" + answersJson + "}"))
                .hasStatusOk();
    }

    private UUID userIdFor(JwtRequestPostProcessor token) throws java.io.IOException {
        String body = getMe(token);
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(body);
        assertThat(m.find()).isTrue();
        return UUID.fromString(m.group(1));
    }

    private int count(String sql, Object param) {
        return jdbc.sql(sql).param(param).query(Integer.class).single();
    }

    private static JwtRequestPostProcessor newUserToken() {
        String sub = "test-" + UUID.randomUUID();
        return jwt().jwt(j -> j.subject(sub));
    }
}
