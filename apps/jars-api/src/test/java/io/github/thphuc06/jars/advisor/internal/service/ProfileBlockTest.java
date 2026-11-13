package io.github.thphuc06.jars.advisor.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.identity.AgeBand;
import io.github.thphuc06.jars.identity.IncomeSituation;
import io.github.thphuc06.jars.identity.InvestorProfile;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The profile block of the system prompt (ADR-0027): what it says for a complete, a partial and a missing profile. */
class ProfileBlockTest {

    private static final Instant ASSESSED = Instant.parse("2026-11-01T03:21:09Z");

    private static InvestorProfile.Risk risk() {
        return new InvestorProfile.Risk(
                "BALANCED",
                "cân bằng",
                52,
                ASSESSED,
                List.of(
                        new InvestorProfile.Answer("Khẩu vị rủi ro", "vừa phải, cân bằng"),
                        new InvestorProfile.Answer("Thời gian đầu tư", "cần dùng tiền sau 5-10 năm")));
    }

    @Test
    void aCompleteProfile_saysEveryPartInWords() {
        String block =
                ProfileBlock.render(new InvestorProfile(risk(), AgeBand.AGE_25_34, IncomeSituation.REGULAR_SALARY));

        assertThat(block)
                .startsWith(ProfileBlock.START)
                .endsWith(ProfileBlock.END)
                .contains("- Khẩu vị rủi ro: cân bằng, điểm 52/100, đánh giá ngày 2026-11-01")
                .contains(
                        "- Câu trả lời của bảng câu hỏi: Khẩu vị rủi ro: vừa phải, cân bằng; Thời gian đầu tư: cần dùng tiền"
                                + " sau 5-10 năm")
                .contains("- Độ tuổi: 25-34 tuổi")
                .contains("- Thu nhập: lương đều đặn hằng tháng")
                .contains("- Hồ sơ đầy đủ: có")
                .doesNotContain(ProfileBlock.MISSING);
    }

    @Test
    void theDateOfTheAssessmentIsTheDateInVietnam() {
        // 20:00 UTC on the 1st is already the 2nd in Ho Chi Minh City
        InvestorProfile.Risk late =
                new InvestorProfile.Risk("BALANCED", "cân bằng", 52, Instant.parse("2026-11-01T20:00:00Z"), List.of());

        assertThat(ProfileBlock.render(new InvestorProfile(late, null, null))).contains("đánh giá ngày 2026-11-02");
    }

    @Test
    void noProfileAtAll_saysEachPartIsMissing_andNothingIsGuessed() {
        String block = ProfileBlock.render(new InvestorProfile(null, null, null));

        assertThat(block)
                .contains("- Khẩu vị rủi ro: CHƯA CÓ")
                .contains("- Độ tuổi: CHƯA CÓ")
                .contains("- Thu nhập: CHƯA CÓ")
                .contains("- Hồ sơ đầy đủ: KHÔNG, còn thiếu khẩu vị rủi ro, độ tuổi, tình hình thu nhập")
                .doesNotContain("điểm")
                .doesNotContain("Câu trả lời");
    }

    @Test
    void aUserWhoAnsweredTheQuestionnaireBeforeTheNewQuestionsExisted_isMissingOnlyTheNewOnes() {
        String block = ProfileBlock.render(new InvestorProfile(risk(), null, null));

        assertThat(block)
                .contains("- Khẩu vị rủi ro: cân bằng, điểm 52/100")
                .contains("- Độ tuổi: CHƯA CÓ")
                .contains("- Thu nhập: CHƯA CÓ")
                .contains("- Hồ sơ đầy đủ: KHÔNG, còn thiếu độ tuổi, tình hình thu nhập");
    }

    @Test
    void aPartialProfile_namesExactlyWhatIsMissing() {
        assertThat(ProfileBlock.render(new InvestorProfile(null, AgeBand.UNDER_25, IncomeSituation.NO_INCOME_YET)))
                .contains("- Độ tuổi: dưới 25 tuổi")
                .contains("- Thu nhập: chưa có thu nhập")
                .contains("- Hồ sơ đầy đủ: KHÔNG, còn thiếu khẩu vị rủi ro")
                .doesNotContain("còn thiếu độ tuổi");
        assertThat(ProfileBlock.render(new InvestorProfile(risk(), AgeBand.AGE_55_PLUS, null)))
                .contains("- Độ tuổi: từ 55 tuổi trở lên")
                .contains("- Hồ sơ đầy đủ: KHÔNG, còn thiếu tình hình thu nhập");
    }

    @Test
    void everyAgeBandAndIncomeSituation_rendersWithoutBracesOrTemplateCharacters() {
        // the model client reads braces in system text as template variables, and ${...} is easy to mistake for one
        for (AgeBand age : AgeBand.values()) {
            for (IncomeSituation income : IncomeSituation.values()) {
                String block = ProfileBlock.render(new InvestorProfile(risk(), age, income));

                assertThat(block).doesNotContain("{").doesNotContain("}").doesNotContain("$");
                assertThat(block).contains(age.label()).contains(income.label());
            }
        }
        assertThat(AdvisorService.SYSTEM_PROMPT)
                .doesNotContain("{")
                .doesNotContain("}")
                .doesNotContain("$");
    }

    @Test
    void theBlockHasNoRoomForAName_theTypeCarriesNoneAndTheTextIsOnlyLabels() {
        // the type has no field for a name, an e-mail or an id: whatever the identity module puts in it is a code, a
        // number, a date or a fixed label (checked end to end in AdvisorApiTest with a user whose name is an attack)
        assertThat(InvestorProfile.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("risk", "ageBand", "incomeSituation");
        assertThat(InvestorProfile.Risk.class.getRecordComponents())
                .extracting(c -> c.getName())
                .containsExactly("level", "levelLabel", "score", "assessedAt", "answers");
    }
}
