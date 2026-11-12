package io.github.thphuc06.jars.insights;

import io.github.thphuc06.jars.shared.Money;
import java.time.YearMonth;

/** Income and spending of one calendar month in Vietnam time. */
public record MonthlyCashflow(YearMonth month, Money income, Money expense) {

    /** Income minus spending; negative when the user spent more than they earned. */
    public Money net() {
        return income.minus(expense);
    }
}
