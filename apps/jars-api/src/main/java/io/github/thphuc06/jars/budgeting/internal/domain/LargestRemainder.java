package io.github.thphuc06.jars.budgeting.internal.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Divides an amount in proportion to weights so that the rounded parts add up to the amount <em>exactly</em> (the
 * largest-remainder, or Hamilton, method). Rounding every part on its own would leave a cent too many or too few; here
 * every part is first rounded down to the smallest unit, then the units still unassigned go one by one to the parts that
 * lost the most in rounding down.
 *
 * <p>Only {@link BigDecimal} is used, never a floating-point number (ADR-0014).
 */
public final class LargestRemainder {

    /** Digits kept while dividing; far more than any weight can need, so a true integer quotient is never cut short. */
    private static final int DIVISION_SCALE = 40;

    private LargestRemainder() {}

    /**
     * @param total the amount to divide; must not have more than {@code scale} decimals
     * @param scale the number of decimals of every part (2 for money, 2 again for a percentage out of 100.00)
     * @param weights relative sizes of the parts: none negative, at least one positive. Only their proportions matter.
     * @return one part per weight, each with {@code scale} decimals, adding up to {@code total}
     */
    public static List<BigDecimal> apportion(BigDecimal total, int scale, List<BigDecimal> weights) {
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("Nothing to divide the amount over");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal weight : weights) {
            if (weight.signum() < 0) {
                throw new IllegalArgumentException("A weight must not be negative: " + weight);
            }
            sum = sum.add(weight);
        }
        if (sum.signum() == 0) {
            throw new IllegalArgumentException("At least one weight must be positive");
        }
        if (total.signum() < 0) {
            throw new IllegalArgumentException("The amount must not be negative: " + total);
        }
        BigInteger units;
        try {
            units = total.setScale(scale, RoundingMode.UNNECESSARY).unscaledValue();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("The amount has more than " + scale + " decimals: " + total, e);
        }

        int n = weights.size();
        BigInteger[] floors = new BigInteger[n];
        BigDecimal[] fractions = new BigDecimal[n];
        BigInteger assigned = BigInteger.ZERO;
        BigDecimal unitsExact = new BigDecimal(units);
        for (int i = 0; i < n; i++) {
            BigDecimal exact = unitsExact.multiply(weights.get(i)).divide(sum, DIVISION_SCALE, RoundingMode.DOWN);
            BigDecimal floor = exact.setScale(0, RoundingMode.DOWN);
            floors[i] = floor.toBigIntegerExact();
            fractions[i] = exact.subtract(floor);
            assigned = assigned.add(floors[i]);
        }

        // the units left over (fewer than n) go to the largest fractional parts; ties go to the larger weight, then the
        // earlier position, so the result is the same on every run
        int leftover = units.subtract(assigned).intValueExact();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, BigDecimal>comparing(i -> fractions[i])
                .reversed()
                .thenComparing(
                        Comparator.<Integer, BigDecimal>comparing(weights::get).reversed())
                .thenComparing(Comparator.naturalOrder()));
        for (int k = 0; k < leftover; k++) {
            int i = order.get(k);
            floors[i] = floors[i].add(BigInteger.ONE);
        }

        List<BigDecimal> parts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            parts.add(new BigDecimal(floors[i], scale));
        }
        return List.copyOf(parts);
    }
}
