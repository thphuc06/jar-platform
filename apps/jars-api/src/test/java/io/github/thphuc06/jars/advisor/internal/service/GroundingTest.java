package io.github.thphuc06.jars.advisor.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroundingTest {

    @Test
    void amountsFoundInTheToolResultsAreGrounded_whateverTheirFormatting() {
        String tools = "[{\"code\":\"NEC\",\"balanceVnd\":\"2500000\"},{\"code\":\"PLAY\",\"balanceVnd\":\"350000\"}]";

        assertThat(Grounding.ungrounded("Hũ NEC có 2.500.000 ₫, hũ PLAY 350,000 ₫.", tools))
                .isEmpty();
    }

    @Test
    void anAmountNoToolReturnedIsReported() {
        assertThat(Grounding.ungrounded("Bạn nên tiết kiệm 1.200.000 ₫ mỗi tháng.", "[{\"balanceVnd\":\"2500000\"}]"))
                .containsExactly("1200000");
    }

    @Test
    void smallNumbersAndYearsAreNotAmounts() {
        assertThat(Grounding.ungrounded("Trong 6 hũ, năm 2026, 3 mục tiêu.", ""))
                .isEmpty();
    }

    @Test
    void aPercentageIsNotAnAmount_howeverLarge() {
        assertThat(Grounding.ungrounded("Ăn uống tăng 1250% so với thường lệ, hay 1.250 %, còn 12500%.", ""))
                .isEmpty();
        assertThat(Grounding.ungrounded("Chi 1.250 ₫ rồi 1.250%.", "")).containsExactly("1250");
    }

    @Test
    void anAmountTheUserWroteIsGrounded() {
        assertThat(Grounding.ungrounded("Với 5.000.000 ₫ bạn có thể...", "Tôi có 5000000 đồng"))
                .isEmpty();
    }
}
