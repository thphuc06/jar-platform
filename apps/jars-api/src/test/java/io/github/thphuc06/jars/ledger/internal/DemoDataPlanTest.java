package io.github.thphuc06.jars.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.shared.BusinessTime;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DemoDataPlanTest {

    static final UUID USER = UUID.fromString("01900000-0000-7000-8000-0000000000aa");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @Test
    void plan_isDeterministicPerUser() {
        assertThat(DemoDataPlan.plan(USER, TODAY)).isEqualTo(DemoDataPlan.plan(USER, TODAY));
        assertThat(DemoDataPlan.plan(UUID.randomUUID(), TODAY)).isNotEqualTo(DemoDataPlan.plan(USER, TODAY));
    }

    @Test
    void plan_coversThreeFullMonthsAndThisMonthUpToYesterday_inVietnamTime() {
        List<DemoDataPlan.Step> steps = DemoDataPlan.plan(USER, TODAY);

        Set<YearMonth> months = steps.stream()
                .map(s -> YearMonth.from(s.occurredAt().atZone(BusinessTime.ZONE)))
                .collect(Collectors.toSet());
        assertThat(months)
                .containsExactlyInAnyOrder(
                        YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(steps)
                .allSatisfy(s -> assertThat(s.occurredAt())
                        .isBefore(TODAY.atStartOfDay(BusinessTime.ZONE).toInstant()));
        assertThat(steps).isSortedAccordingTo((a, b) -> a.occurredAt().compareTo(b.occurredAt()));
    }

    @Test
    void plan_neverSpendsMoreThanAJarHolds_andUsesWholeThousandsOfVnd() {
        Map<JarCode, BigDecimal> balance = new EnumMap<>(JarCode.class);
        for (JarCode code : JarCode.values()) {
            balance.put(code, BigDecimal.ZERO);
        }
        for (DemoDataPlan.Step step : DemoDataPlan.plan(USER, TODAY)) {
            assertThat(step.amount().signum()).isPositive();
            assertThat(step.amount().remainder(new BigDecimal("1000")).signum()).isZero();
            switch (step.kind()) {
                case INCOME -> balance.merge(JarCode.POOL, step.amount(), BigDecimal::add);
                case ALLOCATE -> {
                    balance.merge(JarCode.POOL, step.amount().negate(), BigDecimal::add);
                    balance.merge(step.jar(), step.amount(), BigDecimal::add);
                }
                case EXPENSE -> balance.merge(step.jar(), step.amount().negate(), BigDecimal::add);
            }
            assertThat(balance.values())
                    .as("after %s", step)
                    .allSatisfy(b -> assertThat(b.signum()).isNotNegative());
        }
    }

    @Test
    void plan_hasASalaryAllocatedByTargets_andOneSpendingShockLastMonth() {
        List<DemoDataPlan.Step> september = DemoDataPlan.plan(USER, TODAY).stream()
                .filter(s ->
                        YearMonth.from(s.occurredAt().atZone(BusinessTime.ZONE)).equals(YearMonth.of(2026, 9)))
                .toList();

        BigDecimal allocated = september.stream()
                .filter(s -> s.kind() == DemoDataPlan.Kind.ALLOCATE)
                .map(DemoDataPlan.Step::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(allocated).isEqualByComparingTo(DemoDataPlan.SALARY); // targets sum to 100 % (BR-1)
        assertThat(september)
                .filteredOn(s -> s.amount().compareTo(new BigDecimal("2500000")) == 0
                        && DemoDataPlan.HEALTH.equals(s.categoryId()))
                .hasSize(1);
    }

    /**
     * The plan drops an expense its jar cannot pay; a budget that drops many of them makes the demo poorer without a
     * word (found: with a 15 M salary the September shock was never planned). Full months keep nearly every wish:
     * 4 bills + 47 habits, + 1 shock in the last full month.
     */
    @Test
    void plan_keepsNearlyEveryPlannedExpenseInFullMonths() {
        for (YearMonth month : List.of(YearMonth.of(2026, 8), YearMonth.of(2026, 9))) {
            long expenses = DemoDataPlan.plan(USER, TODAY).stream()
                    .filter(s -> s.kind() == DemoDataPlan.Kind.EXPENSE)
                    .filter(s -> YearMonth.from(s.occurredAt().atZone(BusinessTime.ZONE))
                            .equals(month))
                    .count();
            assertThat(expenses).as("expenses in %s", month).isGreaterThanOrEqualTo(45);
        }
    }
}
