package io.github.thphuc06.jars.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.identity.RiskLevel;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Answers;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Experience;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Horizon;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Liquidity;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.MainGoal;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Result;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.RiskTolerance;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Business rule BR-4, including its boundaries and the cap; then every possible combination of answers. */
class RiskQuestionnaireTest {

    @ParameterizedTest(name = "{0}+{1}+{2}+{3} = {4} → {5}")
    @CsvSource({
        // tolerance,  horizon,       liquidity,      experience,       score, level
        "CONSERVATIVE, YEARS_2_TO_5,  WITHIN_A_MONTH, BEGINNER,         2,     CONSERVATIVE",
        "MODERATE,     YEARS_2_TO_5,  WITHIN_A_MONTH, BEGINNER,         4,     CONSERVATIVE", // upper edge
        "MODERATE,     YEARS_2_TO_5,  WITHIN_A_MONTH, EXPERIENCED,      5,     BALANCED", // lower edge
        "AGGRESSIVE,   YEARS_2_TO_5,  WITHIN_A_MONTH, VERY_EXPERIENCED, 8,     BALANCED", // upper edge
        "AGGRESSIVE,   YEARS_5_TO_10, WITHIN_A_MONTH, EXPERIENCED,      9,     GROWTH", // lower edge
        "AGGRESSIVE,   OVER_10_YEARS, FLEXIBLE,       VERY_EXPERIENCED, 13,    GROWTH" // maximum
    })
    void scoreMapsToLevel_atTheBoundaries(
            RiskTolerance tolerance,
            Horizon horizon,
            Liquidity liquidity,
            Experience experience,
            int score,
            RiskLevel level) {
        Result result = RiskQuestionnaire.assess(
                new Answers(tolerance, horizon, liquidity, experience, MainGoal.SPECIFIC_GOAL));

        assertThat(result).isEqualTo(new Result(score, level, false));
    }

    @Test
    void moneyNeededImmediately_capsAtConservative_whateverTheScore() {
        Result result = RiskQuestionnaire.assess(new Answers(
                RiskTolerance.AGGRESSIVE,
                Horizon.OVER_10_YEARS,
                Liquidity.IMMEDIATE,
                Experience.VERY_EXPERIENCED,
                MainGoal.LONG_TERM_GROWTH));

        assertThat(result).isEqualTo(new Result(10, RiskLevel.CONSERVATIVE, true)); // 10 alone would be GROWTH
    }

    @Test
    void moneyNeededWithinTwoYears_capsAtConservative() {
        Result result = RiskQuestionnaire.assess(new Answers(
                RiskTolerance.AGGRESSIVE,
                Horizon.UNDER_2_YEARS,
                Liquidity.FLEXIBLE,
                Experience.VERY_EXPERIENCED,
                MainGoal.LONG_TERM_GROWTH));

        assertThat(result).isEqualTo(new Result(9, RiskLevel.CONSERVATIVE, true));
    }

    @Test
    void capIsNotReported_whenTheScoreIsConservativeAnyway() {
        Result result = RiskQuestionnaire.assess(new Answers(
                RiskTolerance.CONSERVATIVE,
                Horizon.UNDER_2_YEARS,
                Liquidity.IMMEDIATE,
                Experience.BEGINNER,
                MainGoal.EMERGENCY_FUND));

        assertThat(result).isEqualTo(new Result(0, RiskLevel.CONSERVATIVE, false));
    }

    /** All 3 × 4 × 3 × 3 × 4 = 432 combinations: the properties BR-4 promises hold for every one of them. */
    @Test
    void everyCombination_respectsTheRules() {
        List<Answers> all = allAnswers();

        assertThat(all).hasSize(432);
        for (Answers answers : all) {
            Result result = RiskQuestionnaire.assess(answers);
            boolean needsMoneySoon =
                    answers.liquidity() == Liquidity.IMMEDIATE || answers.horizon() == Horizon.UNDER_2_YEARS;

            assertThat(result.score()).isBetween(0, RiskQuestionnaire.MAX_SCORE);
            if (needsMoneySoon) {
                assertThat(result.level()).as("cap for %s", answers).isEqualTo(RiskLevel.CONSERVATIVE);
            } else {
                assertThat(result.level()).as("level for %s", answers).isEqualTo(levelForScore(result.score()));
                assertThat(result.capped()).isFalse();
            }
            // Q5 is not scored: changing only the main goal never changes the result
            for (MainGoal other : MainGoal.values()) {
                Answers sameButGoal = new Answers(
                        answers.riskTolerance(), answers.horizon(), answers.liquidity(), answers.experience(), other);
                assertThat(RiskQuestionnaire.assess(sameButGoal)).isEqualTo(result);
            }
        }
    }

    private static RiskLevel levelForScore(int score) {
        return score <= 4 ? RiskLevel.CONSERVATIVE : score <= 8 ? RiskLevel.BALANCED : RiskLevel.GROWTH;
    }

    private static List<Answers> allAnswers() {
        List<Answers> all = new ArrayList<>();
        for (RiskTolerance tolerance : RiskTolerance.values()) {
            for (Horizon horizon : Horizon.values()) {
                for (Liquidity liquidity : Liquidity.values()) {
                    for (Experience experience : Experience.values()) {
                        for (MainGoal goal : MainGoal.values()) {
                            all.add(new Answers(tolerance, horizon, liquidity, experience, goal));
                        }
                    }
                }
            }
        }
        return all;
    }
}
