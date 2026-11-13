package io.github.thphuc06.jars.identity;

/**
 * How regular the user's income is (onboarding question 7). It decides how large an emergency fund and how much
 * fixed commitment make sense, which is why the advisor wants it; it is not part of the risk score.
 */
public enum IncomeSituation {
    REGULAR_SALARY("lương đều đặn hằng tháng"),
    IRREGULAR_INCOME("thu nhập không đều (làm tự do, hoa hồng, thời vụ)"),
    NO_INCOME_YET("chưa có thu nhập");

    private final String label;

    IncomeSituation(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
