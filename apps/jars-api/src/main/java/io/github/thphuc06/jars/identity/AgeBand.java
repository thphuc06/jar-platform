package io.github.thphuc06.jars.identity;

/**
 * How old the user is, as a band (onboarding question 6). A band and never a birth date: the advisor only needs the
 * stage of life, and a date of birth is personal data the app has no use for. The codes are what the API sends and the
 * database stores; the label is what the advisor is told.
 */
public enum AgeBand {
    UNDER_25("dưới 25 tuổi"),
    AGE_25_34("25-34 tuổi"),
    AGE_35_44("35-44 tuổi"),
    AGE_45_54("45-54 tuổi"),
    AGE_55_PLUS("từ 55 tuổi trở lên");

    private final String label;

    AgeBand(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
