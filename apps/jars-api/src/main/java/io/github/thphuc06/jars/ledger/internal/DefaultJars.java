package io.github.thphuc06.jars.ledger.internal;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** BR-1: the six jars of the "6 Jars" method plus the hidden POOL jar, created for every new user. */
final class DefaultJars {

    record Spec(JarCode code, String name, @Nullable BigDecimal targetPct) {}

    /** Target percentages of the non-POOL jars sum to exactly 100 (checked by DefaultJarsTest). */
    static final List<Spec> SPECS = List.of(
            new Spec(JarCode.NEC, "Thiết yếu", new BigDecimal("55")),
            new Spec(JarCode.FFA, "Tự do tài chính", new BigDecimal("10")),
            new Spec(JarCode.LTSS, "Tiết kiệm dài hạn", new BigDecimal("10")),
            new Spec(JarCode.EDU, "Giáo dục", new BigDecimal("10")),
            new Spec(JarCode.PLAY, "Hưởng thụ", new BigDecimal("10")),
            new Spec(JarCode.GIVE, "Cho đi", new BigDecimal("5")),
            new Spec(JarCode.POOL, "Chưa phân bổ", null));

    private DefaultJars() {}
}
