package io.github.thphuc06.jars.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money leaves the API as a decimal STRING with two places ("85000.00"), never as a JSON number: JavaScript numbers
 * are doubles and silently round large or fractional amounts (docs/03-api/api-contract.md §1).
 */
public final class MoneyFormat {

    private MoneyFormat() {}

    public static String format(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
