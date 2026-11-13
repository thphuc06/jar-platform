package io.github.thphuc06.jars.identity.internal.domain;

import io.github.thphuc06.jars.identity.InvestorProfile;
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
 * <p>This only describes the user's stated attitude to risk. The advisor uses it to choose which illustrative products
 * to talk about (ADR-0027); it is not an advice or a suitability assessment by a licensed adviser.
 */
public final class RiskQuestionnaire {

    /**
     * A question with the points awarded for each option, in option order, and what choosing each option says about the
     * user in words (for the advisor's prompt, ADR-0027). The topic and the meanings are fixed texts, never user input.
     */
    public record Question(String key, String topic, List<Integer> pointsPerOption, List<String> meanings) {
        int optionCount() {
            return pointsPerOption.size();
        }
    }

    public record Result(int score, RiskLevel level) {}

    public static final List<Question> QUESTIONS = List.of(
            new Question(
                    "RISK_TOLERANCE",
                    "Khẩu vị rủi ro",
                    List.of(0, 2, 4),
                    List.of("thận trọng, tránh thua lỗ", "vừa phải, cân bằng", "mạo hiểm, tối đa hóa lợi nhuận")),
            new Question(
                    "HORIZON",
                    "Thời gian đầu tư",
                    List.of(0, 1, 3, 4),
                    List.of(
                            "cần dùng tiền trong dưới 2 năm",
                            "cần dùng tiền sau 2-5 năm",
                            "cần dùng tiền sau 5-10 năm",
                            "cần dùng tiền sau hơn 10 năm")),
            new Question(
                    "LIQUIDITY",
                    "Nhu cầu rút tiền",
                    List.of(0, 2, 4),
                    List.of("có thể cần rút ngay", "có thể cần rút trong vòng một tháng", "linh hoạt, không gấp")),
            new Question(
                    "EXPERIENCE",
                    "Kinh nghiệm đầu tư",
                    List.of(0, 2, 4),
                    List.of("mới bắt đầu", "có một ít kinh nghiệm", "nhiều kinh nghiệm")),
            new Question(
                    "OBJECTIVE",
                    "Mục tiêu tài chính chính",
                    List.of(0, 4, 1, 2),
                    List.of(
                            "xây quỹ khẩn cấp",
                            "gia tăng tài sản dài hạn",
                            "tiết kiệm cho một mục tiêu cụ thể",
                            "tạo thu nhập thụ động")));

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

    /**
     * What each stored answer says, in question order, for the advisor's prompt. An answer that is not a valid option of
     * its question (it cannot happen for what {@link #score} accepted) is left out rather than guessed.
     */
    public static List<InvestorProfile.Answer> describe(List<Integer> answers) {
        List<InvestorProfile.Answer> described = new java.util.ArrayList<>();
        for (int i = 0; i < QUESTIONS.size() && answers != null && i < answers.size(); i++) {
            Question question = QUESTIONS.get(i);
            Integer choice = answers.get(i);
            if (choice != null && choice >= 0 && choice < question.optionCount()) {
                described.add(new InvestorProfile.Answer(
                        question.topic(), question.meanings().get(choice)));
            }
        }
        return described;
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
