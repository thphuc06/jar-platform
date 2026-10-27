package io.github.thphuc06.jars.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** BR-5 boundaries of {@link VndAmount}. */
class VndAmountValidatorTest {

    final VndAmountValidator validator = new VndAmountValidator();

    @ParameterizedTest
    @ValueSource(strings = {"1", "85000", "85000.00", "1E+5", "99999999999999999"})
    void accepts(String amount) {
        assertThat(validator.isValid(new BigDecimal(amount), null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "85000.5", "0.01", "100000000000000000"})
    void rejects(String amount) {
        assertThat(validator.isValid(new BigDecimal(amount), null)).isFalse();
    }

    @Test
    void nullIsLeftToNotNull() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
