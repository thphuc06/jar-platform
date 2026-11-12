package io.github.thphuc06.jars.identity.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.ProblemTypes;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RiskQuestionnaireTest {

    @Test
    void allSafestAnswers_scoreZeroAndAreConservative() {
        var result = RiskQuestionnaire.score(List.of(0, 0, 0, 0, 0));

        assertThat(result.score()).isZero();
        assertThat(result.level()).isEqualTo(RiskLevel.CONSERVATIVE);
    }

    @Test
    void allMostAggressiveAnswers_scoreHundredAndAreGrowth() {
        // risk 2 (aggressive), horizon 3 (10y+), liquidity 2 (flexible), experience 2 (advanced), objective 1 (grow
        // wealth)
        var result = RiskQuestionnaire.score(List.of(2, 3, 2, 2, 1));

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.level()).isEqualTo(RiskLevel.GROWTH);
    }

    @Test
    void objectiveAnswers_areNotOrderedByRisk() {
        // option 2 "save for a specific goal" (1 point) is more cautious than option 1 "grow wealth" (4 points)
        int specificGoal = RiskQuestionnaire.score(List.of(0, 0, 0, 0, 2)).score();
        int growWealth = RiskQuestionnaire.score(List.of(0, 0, 0, 0, 1)).score();

        assertThat(specificGoal).isEqualTo(5);
        assertThat(growWealth).isEqualTo(20);
    }

    @ParameterizedTest
    @CsvSource({"0, CONSERVATIVE", "34, CONSERVATIVE", "35, BALANCED", "64, BALANCED", "65, GROWTH", "100, GROWTH"})
    void levelBoundaries(int score, RiskLevel expected) {
        assertThat(RiskQuestionnaire.levelFor(score)).isEqualTo(expected);
    }

    @Test
    void middleOfTheRoadAnswers_areBalanced() {
        // option 1 everywhere except the objective "specific goal" (option 2): 2 + 1 + 2 + 2 + 1 = 8 of 20 points = 40
        var result = RiskQuestionnaire.score(List.of(1, 1, 1, 1, 2));

        assertThat(result.score()).isEqualTo(40);
        assertThat(result.level()).isEqualTo(RiskLevel.BALANCED);
    }

    @Test
    void wrongNumberOfAnswers_isRejected() {
        assertThatThrownBy(() -> RiskQuestionnaire.score(List.of(0, 0, 0)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.type()).isEqualTo(ProblemTypes.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("5 câu hỏi");
                });
        assertThatThrownBy(() -> RiskQuestionnaire.score(null)).isInstanceOf(DomainException.class);
    }

    @Test
    void outOfRangeOption_namesTheQuestion() {
        // question 2 (horizon) has 4 options: 0..3
        assertThatThrownBy(() -> RiskQuestionnaire.score(List.of(0, 4, 0, 0, 0)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Câu 2")
                .hasMessageContaining("0 đến 3");
        assertThatThrownBy(() -> RiskQuestionnaire.score(List.of(-1, 0, 0, 0, 0)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Câu 1");
    }

    @Test
    void nullAnswer_isRejected() {
        assertThatThrownBy(() -> RiskQuestionnaire.score(Arrays.asList(0, null, 0, 0, 0)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Câu 2");
    }
}
