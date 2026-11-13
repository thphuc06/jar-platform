package io.github.thphuc06.jars.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.UserApiTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** {@link IdentityApi#investorProfile}: what the advisor gets to know about a user (ADR-0027). */
class InvestorProfileTest extends UserApiTestSupport {

    @Autowired
    IdentityApi identity;

    private void submit(TestUser user, String answers) {
        assertThat(call(HttpMethod.PUT, "/api/v1/me/risk-profile", user.token(), null, "{\"answers\":" + answers + "}")
                        .status())
                .isEqualTo(200);
    }

    private void setFacts(TestUser user, String json) {
        assertThat(patch(user.token(), "/api/v1/me", json).status()).isEqualTo(200);
    }

    @Test
    void aNewUser_hasNothingAnsweredYet() {
        InvestorProfile profile = identity.investorProfile(newTestUser().id());

        assertThat(profile.risk()).isNull();
        assertThat(profile.ageBand()).isNull();
        assertThat(profile.incomeSituation()).isNull();
        assertThat(profile.complete()).isFalse();
    }

    @Test
    void aCompleteProfile_carriesTheLevelTheScoreTheMeaningOfEachAnswerAndTheTwoFacts() {
        TestUser user = newTestUser();
        submit(user, "[2,3,2,2,1]");
        setFacts(user, "{\"ageBand\":\"AGE_25_34\",\"incomeSituation\":\"REGULAR_SALARY\"}");

        InvestorProfile profile = identity.investorProfile(user.id());

        assertThat(profile.complete()).isTrue();
        assertThat(profile.risk().level()).isEqualTo("GROWTH");
        assertThat(profile.risk().levelLabel()).isEqualTo("tăng trưởng (mạo hiểm)");
        assertThat(profile.risk().score()).isEqualTo(100);
        assertThat(profile.risk().assessedAt()).isBetween(Instant.now().minus(Duration.ofMinutes(5)), Instant.now());
        assertThat(profile.risk().answers())
                .extracting(InvestorProfile.Answer::topic)
                .containsExactly(
                        "Khẩu vị rủi ro",
                        "Thời gian đầu tư",
                        "Nhu cầu rút tiền",
                        "Kinh nghiệm đầu tư",
                        "Mục tiêu tài chính chính");
        assertThat(profile.risk().answers())
                .extracting(InvestorProfile.Answer::meaning)
                .containsExactly(
                        "mạo hiểm, tối đa hóa lợi nhuận",
                        "cần dùng tiền sau hơn 10 năm",
                        "linh hoạt, không gấp",
                        "nhiều kinh nghiệm",
                        "gia tăng tài sản dài hạn");
        assertThat(profile.ageBand()).isEqualTo(AgeBand.AGE_25_34);
        assertThat(profile.incomeSituation()).isEqualTo(IncomeSituation.REGULAR_SALARY);
    }

    @Test
    void theLatestQuestionnaireWins() {
        TestUser user = newTestUser();
        submit(user, "[2,3,2,2,1]");
        submit(user, "[0,0,0,0,0]");

        InvestorProfile.Risk risk = identity.investorProfile(user.id()).risk();

        assertThat(risk.level()).isEqualTo("CONSERVATIVE");
        assertThat(risk.score()).isZero();
        assertThat(risk.answers().getFirst().meaning()).isEqualTo("thận trọng, tránh thua lỗ");
    }

    @Test
    void aUserWhoAnsweredTheQuestionnaireBeforeTheTwoNewQuestions_hasARiskProfileAndNothingElse() {
        TestUser user = newTestUser();
        submit(user, "[1,1,1,1,2]");

        InvestorProfile profile = identity.investorProfile(user.id());

        assertThat(profile.risk()).isNotNull();
        assertThat(profile.ageBand()).isNull();
        assertThat(profile.incomeSituation()).isNull();
        assertThat(profile.complete()).isFalse();
    }

    @Test
    void oneUsersProfileIsNeverAnotherUsers() {
        TestUser alice = newTestUser();
        TestUser bob = newTestUser();
        submit(alice, "[2,3,2,2,1]");
        setFacts(
                alice,
                "{\"ageBand\":\"AGE_45_54\",\"incomeSituation\":\"IRREGULAR_INCOME\",\"displayName\":\"Alice\"}");

        InvestorProfile forBob = identity.investorProfile(bob.id());

        assertThat(forBob.risk()).isNull();
        assertThat(forBob.ageBand()).isNull();
        assertThat(forBob.incomeSituation()).isNull();
        assertThat(identity.investorProfile(alice.id()).toString()).doesNotContain("Alice");
    }
}
