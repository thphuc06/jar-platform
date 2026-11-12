package io.github.thphuc06.jars.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class MoneyTest {

    @Test
    void constructor_normalisesToScaleTwo() {
        assertThat(Money.vnd(85000).amount()).hasToString("85000.00");
        assertThat(new Money(new BigDecimal("1.5"), Money.VND).amount()).hasToString("1.50");
    }

    @Test
    void constructor_rejectsMoreThanTwoDecimals_insteadOfRoundingSilently() {
        assertThatThrownBy(() -> new Money(new BigDecimal("0.001"), Money.VND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2 decimals");
    }

    @Test
    void arithmetic_isExactUnlikeDouble() {
        // 0.1 + 0.2 != 0.3 with double; with BigDecimal it is exact
        Money sum = Money.vnd("0.10").plus(Money.vnd("0.20"));

        assertThat(sum).isEqualTo(Money.vnd("0.30"));
    }

    @Test
    void minus_canGoNegative() {
        Money result = Money.vnd(100).minus(Money.vnd(250));

        assertThat(result.isNegative()).isTrue();
        assertThat(result).isEqualTo(Money.vnd(-150));
    }

    @ParameterizedTest
    @CsvSource({
        "1000000, 55, 550000.00",
        "1000000, 10, 100000.00",
        "1000000, 5, 50000.00",
        "333.33, 10, 33.33", // 33.333 rounds down
        "0.05, 50, 0.02", // 0.025 -> banker's rounding goes to the even neighbour 0.02
        "0.15, 50, 0.08" // 0.075 -> 0.08 (even)
    })
    void percentOf_roundsHalfEven(String amount, String percent, String expected) {
        assertThat(Money.vnd(amount).percentOf(new BigDecimal(percent))).isEqualTo(Money.vnd(expected));
    }

    @Test
    void times_usesTheGivenRoundingMode() {
        assertThat(Money.vnd("10.00").times(new BigDecimal("0.333"), RoundingMode.DOWN))
                .isEqualTo(Money.vnd("3.33"));
        assertThat(Money.vnd("10.00").times(new BigDecimal("0.336"), RoundingMode.UP))
                .isEqualTo(Money.vnd("3.36"));
    }

    @Test
    void operationsAcrossCurrencies_areRejected() {
        Money usd = new Money(BigDecimal.ONE, Currency.getInstance("USD"));

        assertThatThrownBy(() -> Money.vnd(1).plus(usd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
        assertThatThrownBy(() -> Money.vnd(1).compareTo(usd)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparison_andSignHelpers() {
        assertThat(Money.vnd(5).isGreaterThan(Money.vnd(4))).isTrue();
        assertThat(Money.zero(Money.VND).isZero()).isTrue();
        assertThat(Money.vnd(1).isPositive()).isTrue();
        assertThat(Money.vnd(1).negate().isNegative()).isTrue();
    }

    @Test
    void json_writesTheAmountAsAStringSoJavaScriptCannotRoundIt() {
        JsonMapper json = JsonMapper.builder().build();

        String out = json.writeValueAsString(Money.vnd("85000"));

        assertThat(out).contains("\"amount\":\"85000.00\"").contains("\"currency\":\"VND\"");
    }

    @Test
    void json_roundTrips() {
        JsonMapper json = JsonMapper.builder().build();
        Money original = Money.vnd("123456.78");

        Money back = json.readValue(json.writeValueAsString(original), Money.class);

        assertThat(back).isEqualTo(original);
    }
}
