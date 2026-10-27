package io.github.thphuc06.jars.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** BR-1 as executable rules: if someone edits the default jar table, these tell whether it is still valid. */
class DefaultJarsTest {

    @Test
    void targetPercentagesOfTheVisibleJars_sumToExactly100() {
        BigDecimal sum = DefaultJars.SPECS.stream()
                .map(DefaultJars.Spec::targetPct)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sum).isEqualByComparingTo("100"); // compareTo: 100 and 100.00 are equal, equals() says they are not
    }

    @Test
    void containsTheSixJarsPlusPool_eachCodeOnce_andNoCustom() {
        assertThat(DefaultJars.SPECS)
                .extracting(DefaultJars.Spec::code)
                .containsExactly(
                        JarCode.NEC, JarCode.FFA, JarCode.LTSS, JarCode.EDU, JarCode.PLAY, JarCode.GIVE, JarCode.POOL);
    }

    @Test
    void onlyPoolHasNoTargetPercentage() {
        assertThat(DefaultJars.SPECS)
                .filteredOn(spec -> spec.targetPct() == null)
                .extracting(DefaultJars.Spec::code)
                .containsExactly(JarCode.POOL);
    }
}
