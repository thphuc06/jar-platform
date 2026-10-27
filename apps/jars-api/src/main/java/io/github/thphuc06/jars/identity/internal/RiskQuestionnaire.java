package io.github.thphuc06.jars.identity.internal;

import io.github.thphuc06.jars.identity.RiskLevel;

/**
 * The onboarding risk questionnaire and its scoring (business-rules BR-4, version {@value #VERSION}). Pure logic: no
 * Spring, no database, so every combination of answers can be tested.
 */
public final class RiskQuestionnaire {

    /** Bump when a question or the scoring changes; stored with every assessment. */
    public static final int VERSION = 1;

    public static final int MAX_SCORE = 13;

    private RiskQuestionnaire() {}

    /** Q1. */
    public enum RiskTolerance {
        CONSERVATIVE(0),
        MODERATE(2),
        AGGRESSIVE(4);

        final int points;

        RiskTolerance(int points) {
            this.points = points;
        }
    }

    /** Q2: when the money will be needed. */
    public enum Horizon {
        UNDER_2_YEARS(0),
        YEARS_2_TO_5(1),
        YEARS_5_TO_10(3),
        OVER_10_YEARS(4);

        final int points;

        Horizon(int points) {
            this.points = points;
        }
    }

    /** Q3. */
    public enum Liquidity {
        IMMEDIATE(0),
        WITHIN_A_MONTH(1),
        FLEXIBLE(3);

        final int points;

        Liquidity(int points) {
            this.points = points;
        }
    }

    /** Q4. */
    public enum Experience {
        BEGINNER(0),
        EXPERIENCED(1),
        VERY_EXPERIENCED(2);

        final int points;

        Experience(int points) {
            this.points = points;
        }
    }

    /** Q5: stored for the advisor, NOT scored. */
    public enum MainGoal {
        EMERGENCY_FUND,
        LONG_TERM_GROWTH,
        SPECIFIC_GOAL,
        PASSIVE_INCOME
    }

    public record Answers(
            RiskTolerance riskTolerance,
            Horizon horizon,
            Liquidity liquidity,
            Experience experience,
            MainGoal mainGoal) {}

    /** {@code capped}: the score alone would give a higher level, but money needed soon caps it at CONSERVATIVE. */
    public record Result(int score, RiskLevel level, boolean capped) {}

    public static Result assess(Answers answers) {
        int score = answers.riskTolerance().points
                + answers.horizon().points
                + answers.liquidity().points
                + answers.experience().points;
        RiskLevel byScore = score <= 4 ? RiskLevel.CONSERVATIVE : score <= 8 ? RiskLevel.BALANCED : RiskLevel.GROWTH;
        // money needed now or within two years should not be exposed to risk, whatever the other answers say
        boolean needsMoneySoon =
                answers.liquidity() == Liquidity.IMMEDIATE || answers.horizon() == Horizon.UNDER_2_YEARS;
        if (needsMoneySoon && byScore != RiskLevel.CONSERVATIVE) {
            return new Result(score, RiskLevel.CONSERVATIVE, true);
        }
        return new Result(score, byScore, false);
    }
}
