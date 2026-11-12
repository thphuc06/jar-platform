package io.github.thphuc06.jars.shared;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money. Always {@link BigDecimal} with scale 2 (matches {@code NUMERIC(19,2)}), never a double
 * (ADR-0014). The amount is serialised as a JSON string so JavaScript clients cannot round it.
 *
 * <p>Constructing a {@code Money} from a value with more than two significant decimals fails instead of rounding
 * silently. Operations that must round ({@link #percentOf}) take the rounding mode explicitly.
 */
public record Money(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, Currency currency)
        implements Comparable<Money> {

    public static final Currency VND = Currency.getInstance("VND");
    private static final int SCALE = 2;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        try {
            amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Money allows at most " + SCALE + " decimals: " + amount, e);
        }
    }

    public static Money vnd(long amount) {
        return new Money(BigDecimal.valueOf(amount), VND);
    }

    public static Money vnd(String amount) {
        return new Money(new BigDecimal(amount), VND);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /** {@code percent} is in the range 0..100; the result is rounded to 2 decimals with banker's rounding. */
    public Money percentOf(BigDecimal percent) {
        BigDecimal result = amount.multiply(percent).divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_EVEN);
        return new Money(result, currency);
    }

    /** Multiplies by a factor, rounding to 2 decimals with the given mode. */
    public Money times(BigDecimal factor, RoundingMode mode) {
        return new Money(amount.multiply(factor).setScale(SCALE, mode), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
