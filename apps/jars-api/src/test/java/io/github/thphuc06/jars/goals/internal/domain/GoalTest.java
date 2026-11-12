package io.github.thphuc06.jars.goals.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GoalTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");
    private static final Instant LATER = Instant.parse("2026-11-13T03:00:00Z");

    private static Goal goal() {
        return Goal.create(USER, "Mua nhà", Money.vnd("1500000000"), LocalDate.of(2029, 11, 12), null, TODAY, NOW);
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.type().status()).isEqualTo(422);
            assertThat(e.type().slug()).isEqualTo("invalid-goal");
        });
    }

    @Test
    void create_startsActive_withTheGivenValues_andATrimmedName() {
        UUID jar = UUID.randomUUID();

        Goal g = Goal.create(USER, "  Mua nhà  ", Money.vnd("1500000000"), LocalDate.of(2029, 11, 12), jar, TODAY, NOW);

        assertThat(g.getName()).isEqualTo("Mua nhà");
        assertThat(g.target()).isEqualTo(Money.vnd("1500000000"));
        assertThat(g.getTargetDate()).isEqualTo(LocalDate.of(2029, 11, 12));
        assertThat(g.getFundingJarId()).isEqualTo(jar);
        assertThat(g.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(g.getUserId()).isEqualTo(USER);
        assertThat(g.getCreatedAt()).isEqualTo(NOW);
        assertThat(g.getUpdatedAt()).isEqualTo(NOW);
        assertThat(g.getArchivedAt()).isNull();
        assertThat(g.isArchived()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void create_rejectsABlankName(String name) {
        assertInvalid(() -> Goal.create(USER, name, Money.vnd("1000"), TODAY.plusDays(30), null, TODAY, NOW));
    }

    @Test
    void create_rejectsANullName_andANameOverEightyCharacters() {
        assertInvalid(() -> Goal.create(USER, null, Money.vnd("1000"), TODAY.plusDays(30), null, TODAY, NOW));
        assertInvalid(() -> Goal.create(USER, "x".repeat(81), Money.vnd("1000"), TODAY.plusDays(30), null, TODAY, NOW));
        assertThat(Goal.create(USER, "x".repeat(80), Money.vnd("1000"), TODAY.plusDays(30), null, TODAY, NOW)
                        .getName())
                .hasSize(80);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01"})
    void create_rejectsATargetThatIsNotPositive(String amount) {
        assertInvalid(() -> Goal.create(USER, "Quỹ", Money.vnd(amount), TODAY.plusDays(30), null, TODAY, NOW));
    }

    @Test
    void create_rejectsADateThatIsNotAfterToday() {
        assertInvalid(() -> Goal.create(USER, "Quỹ", Money.vnd("1000"), TODAY, null, TODAY, NOW));
        assertInvalid(() -> Goal.create(USER, "Quỹ", Money.vnd("1000"), TODAY.minusDays(1), null, TODAY, NOW));
        assertInvalid(() -> Goal.create(USER, "Quỹ", Money.vnd("1000"), null, null, TODAY, NOW));
        assertThat(Goal.create(USER, "Quỹ", Money.vnd("1000"), TODAY.plusDays(1), null, TODAY, NOW)
                        .getTargetDate())
                .isEqualTo(TODAY.plusDays(1));
    }

    @Test
    void theEditsChangeTheFieldAndTheUpdateTime() {
        Goal g = goal();
        UUID jar = UUID.randomUUID();

        g.rename("  Mua căn hộ ", LATER);
        g.changeTarget(Money.vnd("2000000000"), LATER);
        g.changeTargetDate(LocalDate.of(2030, 1, 1), TODAY, LATER);
        g.fundWith(jar, LATER);

        assertThat(g.getName()).isEqualTo("Mua căn hộ");
        assertThat(g.target()).isEqualTo(Money.vnd("2000000000"));
        assertThat(g.getTargetDate()).isEqualTo(LocalDate.of(2030, 1, 1));
        assertThat(g.getFundingJarId()).isEqualTo(jar);
        assertThat(g.getUpdatedAt()).isEqualTo(LATER);
        assertThat(g.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void theEditsApplyTheSameRulesAsCreate() {
        Goal g = goal();

        assertInvalid(() -> g.rename(" ", LATER));
        assertInvalid(() -> g.changeTarget(Money.vnd("0"), LATER));
        assertInvalid(() -> g.changeTargetDate(TODAY, TODAY, LATER));
        assertThat(g.getName()).isEqualTo("Mua nhà");
        assertThat(g.getUpdatedAt()).as("a rejected edit changes nothing").isEqualTo(NOW);
    }

    @Test
    void markAchieved_andReactivate_moveBetweenActiveAndAchieved_andRepeatingChangesNothing() {
        Goal g = goal();

        g.markAchieved(LATER);
        assertThat(g.getStatus()).isEqualTo(GoalStatus.ACHIEVED);
        assertThat(g.getUpdatedAt()).isEqualTo(LATER);

        g.markAchieved(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(g.getUpdatedAt()).as("already achieved: untouched").isEqualTo(LATER);

        g.reactivate(Instant.parse("2026-12-02T00:00:00Z"));
        assertThat(g.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        g.reactivate(Instant.parse("2026-12-03T00:00:00Z"));
        assertThat(g.getUpdatedAt()).isEqualTo(Instant.parse("2026-12-02T00:00:00Z"));
    }

    @Test
    void archive_marksTheGoalArchived_once() {
        Goal g = goal();

        g.archive(LATER);
        g.archive(Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(g.isArchived()).isTrue();
        assertThat(g.getStatus()).isEqualTo(GoalStatus.ARCHIVED);
        assertThat(g.getArchivedAt()).as("the first time stays").isEqualTo(LATER);
        assertThat(g.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void anArchivedGoal_cannotBecomeAchievedOrActiveAgain() {
        Goal g = goal();
        g.archive(LATER);

        g.markAchieved(LATER.plusSeconds(1));
        g.reactivate(LATER.plusSeconds(2));

        assertThat(g.getStatus()).isEqualTo(GoalStatus.ARCHIVED);
    }
}
