package io.github.thphuc06.jars.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.shared.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The balance rules of a jar, without Spring or a database. */
class JarTest {

    static final UUID TXN = UUID.randomUUID();

    private static Jar play() {
        return Jar.open(UUID.randomUUID(), JarCode.PLAY, "Hưởng thụ", new BigDecimal("10"));
    }

    @Test
    void deposit_raisesBalance_andRecordsAPositiveEntryWithTheNewBalance() {
        Jar jar = play();

        JarEntry entry = jar.deposit(new BigDecimal("100000.00"), EntryType.TRANSFER_IN, TXN);

        assertThat(jar.balance()).isEqualByComparingTo("100000");
        assertThat(entry.amount()).isEqualByComparingTo("100000");
        assertThat(entry.balanceAfter()).isEqualByComparingTo("100000");
        assertThat(entry.jarId()).isEqualTo(jar.id());
    }

    @Test
    void withdraw_lowersBalance_andRecordsANegativeEntry() {
        Jar jar = play();
        jar.deposit(new BigDecimal("100000.00"), EntryType.TRANSFER_IN, TXN);

        JarEntry entry = jar.withdraw(new BigDecimal("30000.00"), EntryType.SPEND, TXN);

        assertThat(jar.balance()).isEqualByComparingTo("70000");
        assertThat(entry.amount()).isEqualByComparingTo("-30000");
        assertThat(entry.balanceAfter()).isEqualByComparingTo("70000");
    }

    @Test
    void withdraw_exactBalance_leavesZero() {
        Jar jar = play();
        jar.deposit(new BigDecimal("50000.00"), EntryType.TRANSFER_IN, TXN);

        jar.withdraw(new BigDecimal("50000.00"), EntryType.SPEND, TXN);

        assertThat(jar.balance()).isZero();
    }

    @Test
    void withdraw_moreThanBalance_isRejected_andLeavesTheBalanceUntouched() {
        Jar jar = play();
        jar.deposit(new BigDecimal("50000.00"), EntryType.TRANSFER_IN, TXN);

        assertThatThrownBy(() -> jar.withdraw(new BigDecimal("50001.00"), EntryType.SPEND, TXN))
                .isInstanceOfSatisfying(
                        BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("insufficient-jar-balance"));
        assertThat(jar.balance()).isEqualByComparingTo("50000");
    }

    @Test
    void entryTypeMustMatchTheDirection() {
        Jar jar = play();

        assertThatThrownBy(() -> jar.deposit(BigDecimal.TEN, EntryType.SPEND, TXN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jar.withdraw(BigDecimal.TEN, EntryType.INCOME, TXN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jar.deposit(BigDecimal.ZERO, EntryType.INCOME, TXN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
