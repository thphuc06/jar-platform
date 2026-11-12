package io.github.thphuc06.jars.ledger.internal.domain;

import java.math.BigDecimal;
import java.util.List;

/** The six jars of the "6 Jars" method plus {@code CUSTOM} for jars the user adds. */
public enum JarCode {
    NEC("Thiết yếu", 55),
    FFA("Tự do tài chính", 10),
    LTSS("Tiết kiệm dài hạn", 10),
    EDU("Giáo dục", 10),
    PLAY("Hưởng thụ", 10),
    GIVE("Cho đi", 5),
    CUSTOM("Hũ tùy chỉnh", 0);

    private final String displayName;
    private final BigDecimal defaultPct;

    JarCode(String displayName, int defaultPct) {
        this.displayName = displayName;
        this.defaultPct = BigDecimal.valueOf(defaultPct);
    }

    public String displayName() {
        return displayName;
    }

    /** Suggested share of income in percent; the six defaults add up to 100. */
    public BigDecimal defaultPct() {
        return defaultPct;
    }

    /** The jars every new user gets, in display order. */
    public static List<JarCode> defaults() {
        return List.of(NEC, FFA, LTSS, EDU, PLAY, GIVE);
    }
}
