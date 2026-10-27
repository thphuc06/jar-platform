package io.github.thphuc06.jars.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import io.github.thphuc06.jars.TestcontainersConfiguration;
import io.github.thphuc06.jars.identity.RiskLevel;
import io.github.thphuc06.jars.identity.RiskProfileAssessed;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** PUT/GET /me/risk-profile end to end on PostgreSQL 17 (business-rules BR-4). */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class RiskProfileIntegrationTest {

    static final SimpleGrantedAuthority READ = new SimpleGrantedAuthority("SCOPE_jars-api/read");
    static final SimpleGrantedAuthority WRITE = new SimpleGrantedAuthority("SCOPE_jars-api/write");
    static final String CAUTIOUS = """
            {"riskTolerance": "AGGRESSIVE", "horizon": "UNDER_2_YEARS", "liquidity": "FLEXIBLE",
             "experience": "VERY_EXPERIENCED", "mainGoal": "SPECIFIC_GOAL"}
            """;
    static final String LONG_TERM = """
            {"riskTolerance": "AGGRESSIVE", "horizon": "OVER_10_YEARS", "liquidity": "FLEXIBLE",
             "experience": "EXPERIENCED", "mainGoal": "LONG_TERM_GROWTH"}
            """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    RiskAssessmentRepository assessments;

    @Autowired
    ApplicationEvents events;

    @MockitoBean
    UserInfoClient userInfo;

    String sub;

    @BeforeEach
    void newCognitoUser() {
        sub = "sub-" + UUID.randomUUID();
        given(userInfo.fetch(anyString())).willReturn(new UserInfoClient.UserInfo(sub, sub + "@example.com"));
    }

    @Test
    void beforeAnswering_theProfileIs404() {
        assertThat(get())
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.type")
                .isEqualTo("https://jars.local/problems/risk-profile-not-found");
    }

    @Test
    void answering_scoresAndStoresTheProfile() {
        assertThat(put(CAUTIOUS)).hasStatus(HttpStatus.OK).bodyJson().isLenientlyEqualTo("""
                        {"score": 9, "maxScore": 13, "riskLevel": "CONSERVATIVE", "capped": true,
                         "questionnaireVersion": 1, "answers": {"mainGoal": "SPECIFIC_GOAL"}}
                        """);
        assertThat(get())
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.score")
                .isEqualTo(9);
    }

    @Test
    void answeringAgain_keepsTheHistory_andTheNewestCounts() {
        put(CAUTIOUS);
        put(LONG_TERM);

        assertThat(get()).bodyJson().isLenientlyEqualTo("""
                        {"score": 12, "riskLevel": "GROWTH", "capped": false}
                        """);
        UUID userId =
                events.stream(RiskProfileAssessed.class).toList().getLast().userId();
        assertThat(assessments.countByUserId(userId)).isEqualTo(2);
        assertThat(events.stream(RiskProfileAssessed.class)
                        .filter(e -> e.userId().equals(userId)))
                .extracting(RiskProfileAssessed::riskLevel)
                .containsExactly(RiskLevel.CONSERVATIVE, RiskLevel.GROWTH);
    }

    @Test
    void missingAnswer_isAValidationError() {
        MvcTestResult result = put("""
                {"riskTolerance": "MODERATE", "horizon": "YEARS_2_TO_5", "liquidity": "FLEXIBLE",
                 "experience": "BEGINNER"}
                """);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("mainGoal");
    }

    @Test
    void unknownAnswerValue_is400() {
        assertThat(put(CAUTIOUS.replace("FLEXIBLE", "WHENEVER"))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void answeringWithAReadOnlyToken_is403() {
        assertThat(mvc.put()
                        .uri("/api/v1/me/risk-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CAUTIOUS)
                        .with(jwt().jwt(j -> j.subject(sub)).authorities(READ))
                        .exchange())
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    private MvcTestResult get() {
        return mvc.get()
                .uri("/api/v1/me/risk-profile")
                .with(jwt().jwt(j -> j.subject(sub)).authorities(READ))
                .exchange();
    }

    private MvcTestResult put(String json) {
        return mvc.put()
                .uri("/api/v1/me/risk-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(jwt().jwt(j -> j.subject(sub)).authorities(WRITE))
                .exchange();
    }
}
