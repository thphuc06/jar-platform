package io.github.thphuc06.jars.budgeting.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LargestRemainderTest {

    private static List<BigDecimal> weights(String... values) {
        return Stream.of(values).map(BigDecimal::new).toList();
    }

    static Stream<Arguments> goldenCases() {
        return Stream.of(
                // three equal parts of 100.00: the unit left over goes to the first of the equal remainders
                Arguments.of("100.00", 2, weights("1", "1", "1"), List.of("33.34", "33.33", "33.33")),
                Arguments.of("10", 0, weights("1", "1", "1"), List.of("4", "3", "3")),
                // Hamilton's textbook example: floors 12+6+3+2 = 23, the two spare seats go to the largest remainders
                // (.75 and .75; the tie goes to the larger weight), leaving .5 without
                Arguments.of("25", 0, weights("51", "24", "15", "10"), List.of("13", "6", "4", "2")),
                // the six default jars: exact, nothing to distribute
                Arguments.of(
                        "10000000.00",
                        2,
                        weights("55", "10", "10", "10", "10", "5"),
                        List.of("5500000.00", "1000000.00", "1000000.00", "1000000.00", "1000000.00", "500000.00")),
                // the smallest unit of money is shared by remainder, not rounded away
                Arguments.of("0.05", 2, weights("1", "1"), List.of("0.03", "0.02")),
                Arguments.of("0.01", 2, weights("1", "1", "1"), List.of("0.01", "0.00", "0.00")),
                // a weight of zero never receives anything
                Arguments.of("100.00", 2, weights("0", "1", "0", "1"), List.of("0.00", "50.00", "0.00", "50.00")),
                // only proportions matter, not the size of the weights
                Arguments.of("100.00", 2, weights("0.3333", "0.3333", "0.3334"), List.of("33.33", "33.33", "33.34")),
                Arguments.of("100.00", 2, weights("3333", "3333", "3334"), List.of("33.33", "33.33", "33.34")),
                Arguments.of("100.00", 2, weights("7"), List.of("100.00")),
                Arguments.of("0.00", 2, weights("1", "2"), List.of("0.00", "0.00")),
                // an amount with trailing zeros beyond the scale is accepted
                Arguments.of("12.3400", 2, weights("1", "1"), List.of("6.17", "6.17")),
                // 1 000 000.07 over the legacy-style 50/30/20 split
                Arguments.of(
                        "1000000.07", 2, weights("50", "30", "20"), List.of("500000.04", "300000.02", "200000.01")));
    }

    @ParameterizedTest(name = "{0} over {2}")
    @MethodSource("goldenCases")
    void apportion_matchesTheGoldenResult(String total, int scale, List<BigDecimal> weights, List<String> expected) {
        List<BigDecimal> parts = LargestRemainder.apportion(new BigDecimal(total), scale, weights);

        assertThat(parts)
                .containsExactlyElementsOf(
                        expected.stream().map(BigDecimal::new).toList());
        assertThat(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(total);
        parts.forEach(p -> assertThat(p.scale()).isEqualTo(scale));
    }

    @Test
    void apportion_alwaysAddsUpToTheAmount_andStaysWithinOneUnitOfTheExactShare() {
        Random random = new Random(20261112L); // fixed seed: the test is reproducible
        for (int round = 0; round < 2_000; round++) {
            int n = 1 + random.nextInt(9);
            List<BigDecimal> weights = new ArrayList<>();
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < n; i++) {
                BigDecimal w = BigDecimal.valueOf(random.nextInt(10_000), 2); // 0.00 .. 99.99, zeros allowed
                weights.add(w);
                sum = sum.add(w);
            }
            if (sum.signum() == 0) {
                weights.set(0, BigDecimal.ONE);
                sum = BigDecimal.ONE;
            }
            BigDecimal total = BigDecimal.valueOf(random.nextLong(1_000_000_000_000L), 2);

            List<BigDecimal> parts = LargestRemainder.apportion(total, 2, weights);

            assertThat(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(total);
            for (int i = 0; i < n; i++) {
                BigDecimal exact = total.multiply(weights.get(i)).divide(sum, 10, java.math.RoundingMode.HALF_EVEN);
                assertThat(parts.get(i).subtract(exact).abs())
                        .as("part %d of %s over %s", i, total, weights)
                        .isLessThanOrEqualTo(new BigDecimal("0.01"));
                assertThat(parts.get(i).signum()).isGreaterThanOrEqualTo(0);
                if (weights.get(i).signum() == 0) {
                    assertThat(parts.get(i)).isEqualByComparingTo("0");
                }
            }
        }
    }

    @Test
    void apportion_isDeterministic() {
        List<BigDecimal> w = weights("1", "1", "1", "1", "1", "1", "1");

        assertThat(LargestRemainder.apportion(new BigDecimal("100.00"), 2, w))
                .isEqualTo(LargestRemainder.apportion(new BigDecimal("100.00"), 2, w));
    }

    @Test
    void apportion_rejectsInputsThatCannotBeDivided() {
        BigDecimal hundred = new BigDecimal("100.00");

        assertThatThrownBy(() -> LargestRemainder.apportion(hundred, 2, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LargestRemainder.apportion(hundred, 2, weights("1", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> LargestRemainder.apportion(hundred, 2, weights("0", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> LargestRemainder.apportion(new BigDecimal("-1.00"), 2, weights("1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> LargestRemainder.apportion(new BigDecimal("1.005"), 2, weights("1", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimals");
    }
}
