package io.github.thphuc06.jars.ledger.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JarTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");

    private final Jar jar = new Jar(UUID.randomUUID(), JarCode.NEC, "Thiết yếu", new BigDecimal("55"), Money.VND, NOW);

    @Test
    void newJar_startsEmptyWithScaleTwo() {
        assertThat(jar.balance()).isEqualTo(Money.zero(Money.VND));
        assertThat(jar.getTargetPct()).hasToString("55.00");
        assertThat(jar.isArchived()).isFalse();
    }

    @Test
    void post_movesTheBalanceAndReturnsAMatchingEntry() {
        JarEntry credit = jar.post(EntryType.ALLOCATE, Money.vnd(1_000_000), UUID.randomUUID(), NOW, NOW);
        JarEntry debit = jar.post(EntryType.SPEND, Money.vnd(-250_000), UUID.randomUUID(), NOW, NOW);

        assertThat(jar.balance()).isEqualTo(Money.vnd(750_000));
        assertThat(credit.getAmount()).isEqualByComparingTo("1000000");
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo("1000000");
        assertThat(debit.getAmount()).isEqualByComparingTo("-250000");
        assertThat(debit.getBalanceAfter()).isEqualByComparingTo("750000");
        assertThat(debit.getEntryType()).isEqualTo(EntryType.SPEND);
    }

    @Test
    void post_allowsTheBalanceToGoNegative_becauseTheMoneyIsAlreadySpent() {
        jar.post(EntryType.SPEND, Money.vnd(-100), UUID.randomUUID(), NOW, NOW);

        assertThat(jar.balance().isNegative()).isTrue();
    }

    @Test
    void post_rejectsAZeroEntry() {
        assertThatThrownBy(() -> jar.post(EntryType.ADJUST, Money.zero(Money.VND), UUID.randomUUID(), NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireAtLeast_failsWithAnInsufficientBalanceProblem() {
        jar.post(EntryType.ALLOCATE, Money.vnd(100), UUID.randomUUID(), NOW, NOW);

        jar.requireAtLeast(Money.vnd(100)); // exactly enough is fine
        assertThatThrownBy(() -> jar.requireAtLeast(Money.vnd(101)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.type()).isEqualTo(LedgerProblems.INSUFFICIENT_JAR_BALANCE);
                    assertThat(e.getMessage()).contains("Thiết yếu");
                });
    }

    @Test
    void archive_needsAnEmptyJarAndReleasesItsShareOfTheBudget() {
        jar.post(EntryType.ALLOCATE, Money.vnd(5), UUID.randomUUID(), NOW, NOW);
        assertThatThrownBy(() -> jar.archive(NOW))
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.type()).isEqualTo(LedgerProblems.JAR_NOT_EMPTY));

        jar.post(EntryType.SPEND, Money.vnd(-5), UUID.randomUUID(), NOW, NOW);
        jar.archive(NOW);

        assertThat(jar.isArchived()).isTrue();
        assertThat(jar.getTargetPct()).isEqualByComparingTo("0");
    }

    @Test
    void archivedJar_rejectsFurtherPostings() {
        jar.archive(NOW);

        assertThatThrownBy(() -> jar.post(EntryType.ALLOCATE, Money.vnd(1), UUID.randomUUID(), NOW, NOW))
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.type()).isEqualTo(LedgerProblems.JAR_ARCHIVED));
    }

    @Test
    void renameAndRetarget_updateTheJar() {
        jar.rename("Chi tiêu hằng ngày", NOW);
        jar.changeTargetPct(new BigDecimal("40.5"), NOW);

        assertThat(jar.getName()).isEqualTo("Chi tiêu hằng ngày");
        assertThat(jar.getTargetPct()).hasToString("40.50");
    }

    @Test
    void defaultJars_areSixAndTheirSharesAddUpToOneHundred() {
        BigDecimal total =
                JarCode.defaults().stream().map(JarCode::defaultPct).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(JarCode.defaults()).hasSize(6).doesNotContain(JarCode.CUSTOM);
        assertThat(total).isEqualByComparingTo("100");
    }
}
