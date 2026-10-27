package io.github.thphuc06.jars.ledger.internal.web;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money leaves the API as a decimal STRING with two places ("85000.00"), never as a JSON number: JavaScript numbers
 * are doubles and silently round large or fractional amounts (docs/03-api/api-contract.md §1).
 */
final class MoneyJson {

    private MoneyJson() {}

    static String format(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
