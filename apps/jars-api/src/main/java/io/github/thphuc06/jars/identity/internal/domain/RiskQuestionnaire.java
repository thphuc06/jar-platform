package io.github.thphuc06.jars.identity.internal.domain;

import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.ProblemTypes;
import java.util.List;

/**
 * The five onboarding questions and how answers become a score. The option order matches the UI
 * (apps/web Onboarding page); the answer is the index of the chosen option.
 *
 * <p>Each answer is worth 0..4 points, 20 in total, and the score is that total scaled to 0..100. The table is
 * explicit rather than "index × k" because some questions are not ordered by risk (for example the objective
 * "save for a specific goal" is more cautious than "grow wealth").
 *
 * <p>This only describes the user's stated attitude to risk. The product gives no investment advice (vision-and-scope).
 */
public final class RiskQuestionnaire {

    /** A question with the points awarded for each option, in option order. */
    public record Question(String key, List<Integer> pointsPerOption) {
        int optionCount() {
            return pointsPerOption.size();
        }
    }

    public record Result(int score, RiskLevel level) {}

    public static final List<Question> QUESTIONS = List.of(
            new Question("RISK_TOLERANCE", List.of(0, 2, 4)), // conservative, moderate, aggressive
            new Question("HORIZON", List.of(0, 1, 3, 4)), // <2y, 2-5y, 5-10y, 10y+
            new Question("LIQUIDITY", List.of(0, 2, 4)), // immediate, within a month, flexible
            new Question("EXPERIENCE", List.of(0, 2, 4)), // beginner, intermediate, advanced
            new Question(
                    "OBJECTIVE", List.of(0, 4, 1, 2))); // emergency fund, grow wealth, specific goal, passive income

    private static final int MAX_POINTS = QUESTIONS.stream()
            .mapToInt(q -> q.pointsPerOption().stream().mapToInt(i -> i).max().orElse(0))
            .sum();

    static final int BALANCED_FROM = 35;
    static final int GROWTH_FROM = 65;

    private RiskQuestionnaire() {}

    /** @throws DomainException (422) when the number of answers or an option index is wrong */
    public static Result score(List<Integer> answers) {
        if (answers == null || answers.size() != QUESTIONS.size()) {
            throw invalid("Cần trả lời đủ " + QUESTIONS.size() + " câu hỏi.");
        }
        int total = 0;
        for (int i = 0; i < QUESTIONS.size(); i++) {
            Question question = QUESTIONS.get(i);
            Integer choice = answers.get(i);
            if (choice == null || choice < 0 || choice >= question.optionCount()) {
                throw invalid("Câu " + (i + 1) + " chỉ có các lựa chọn từ 0 đến " + (question.optionCount() - 1) + ".");
            }
            total += question.pointsPerOption().get(choice);
        }
        int score = Math.round(total * 100f / MAX_POINTS);
        return new Result(score, levelFor(score));
    }

    static RiskLevel levelFor(int score) {
        if (score >= GROWTH_FROM) {
            return RiskLevel.GROWTH;
        }
        return score >= BALANCED_FROM ? RiskLevel.BALANCED : RiskLevel.CONSERVATIVE;
    }

    private static DomainException invalid(String detail) {
        return new DomainException(ProblemTypes.VALIDATION_FAILED, detail);
    }
}
