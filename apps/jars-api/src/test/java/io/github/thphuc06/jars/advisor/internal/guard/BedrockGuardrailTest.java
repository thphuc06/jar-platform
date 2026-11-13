package io.github.thphuc06.jars.advisor.internal.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BedrockGuardrailTest {

    @Test
    void jarCodesReachTheGuardrailAsJarNames_soTheyAreNotMistakenForTickers() {
        // "Số dư hũ NEC" was blocked as investment advice on AWS before this
        assertThat(BedrockGuardrail.forGuardrail("Số dư hũ NEC của tôi? Còn PLAY và GIVE?"))
                .isEqualTo("Số dư hũ Thiết yếu của tôi? Còn Hưởng thụ và Cho đi?");
        assertThat(BedrockGuardrail.forGuardrail("Hũ thiết yếu (NEC) có 2.500.000 ₫"))
                .doesNotContain("NEC");
    }

    @Test
    void onlyWholeWordsAreReplaced_soATickerOrAWordThatContainsACodeStaysAsItIs() {
        assertThat(BedrockGuardrail.forGuardrail("Tôi có nên mua FPT hay EDUCATION fund?"))
                .isEqualTo("Tôi có nên mua FPT hay EDUCATION fund?");
    }
}
