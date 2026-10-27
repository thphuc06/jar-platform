package io.github.thphuc06.jars.goals.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** BR-6 progress: rounded down, capped at 100. */
class GoalViewTest {

    @ParameterizedTest(name = "{0} of {1} → {2} %")
    @CsvSource({
        "0.00,        1000000.00, 0",
        "249999.00,   1000000.00, 24",
        "999999.00,   1000000.00, 99", // 99.9999 %: never shown as reached
        "1000000.00,  1000000.00, 100",
        "5000000.00,  1000000.00, 100" // more than enough: capped
    })
    void progressPct(String balance, String target, int expected) {
        assertThat(GoalView.progressPct(new BigDecimal(balance), new BigDecimal(target)))
                .isEqualTo(expected);
    }
}
