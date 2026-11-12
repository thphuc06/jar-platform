package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Amounts in the texts of the roadmap: whole dong with Vietnamese grouping, for example {@code 1.500.000 ₫}. */
final class Vnd {

    private Vnd() {}

    static String format(BigDecimal amount) {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"));
        return format.format(amount.setScale(0, RoundingMode.HALF_EVEN)) + " ₫";
    }
}
