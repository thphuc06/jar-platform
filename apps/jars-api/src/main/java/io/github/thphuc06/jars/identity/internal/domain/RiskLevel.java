package io.github.thphuc06.jars.identity.internal.domain;

/** Result of the onboarding questionnaire: how much volatility the user says they accept. */
public enum RiskLevel {
    CONSERVATIVE("thận trọng"),
    BALANCED("cân bằng"),
    GROWTH("tăng trưởng (mạo hiểm)");

    private final String label;

    RiskLevel(String label) {
        this.label = label;
    }

    /** The level in words, for the advisor. */
    public String label() {
        return label;
    }
}
