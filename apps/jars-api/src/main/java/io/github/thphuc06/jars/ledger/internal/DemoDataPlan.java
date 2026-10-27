package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.shared.BusinessTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Demo data for one user, as a list of steps: a salary each month, allocated to the jars by their target percentage,
 * then everyday spending, a few fixed bills, a freelance income and one large unexpected expense (a "spending shock"
 * for the anomaly detection of Phase 2). Pure and deterministic: the same user and day always give the same plan.
 *
 * <p>It simulates the jar balances while planning, so an expense is only planned when its jar can pay it; the
 * seeder then never hits {@code insufficient-jar-balance}.
 */
final class DemoDataPlan {

    enum Kind {
        INCOME,
        ALLOCATE,
        EXPENSE
    }

    /** {@code jar} is the jar that pays (EXPENSE) or receives (ALLOCATE); null for INCOME (lands in POOL). */
    record Step(
            Kind kind,
            Instant occurredAt,
            BigDecimal amount,
            @Nullable JarCode jar,
            @Nullable UUID categoryId,
            @Nullable String counterparty) {}

    static final BigDecimal SALARY = new BigDecimal("20000000");
    static final int MONTHS_BACK = 3;

    // system categories of Flyway V3 (fixed ids)
    static final UUID FOOD = category("001");
    static final UUID GROCERIES = category("002");
    static final UUID HOME = category("003");
    static final UUID TRANSPORT = category("004");
    static final UUID HEALTH = category("005");
    static final UUID LEARNING = category("006");
    static final UUID SHOPPING = category("007");
    static final UUID FUN = category("008");
    static final UUID INVESTING = category("00a");
    static final UUID GIVING = category("00b");
    static final UUID SALARY_CATEGORY = category("101");
    static final UUID FREELANCE = category("102");

    /** Everyday spending: category, paying jar, merchants, amount range in thousands of VND, times per month. */
    private record Habit(UUID category, JarCode jar, List<String> merchants, int minK, int maxK, int perMonth) {}

    private static final List<Habit> HABITS = List.of(
            new Habit(
                    FOOD,
                    JarCode.NEC,
                    List.of("Phở Thìn", "GrabFood", "Highlands Coffee", "Cơm tấm Cali"),
                    30,
                    120,
                    24),
            new Habit(GROCERIES, JarCode.NEC, List.of("Bách Hóa Xanh", "Co.opmart", "WinMart"), 150, 600, 4),
            new Habit(TRANSPORT, JarCode.NEC, List.of("Grab", "Be", "Petrolimex"), 20, 80, 10),
            new Habit(HEALTH, JarCode.NEC, List.of("Pharmacity", "Long Châu"), 50, 400, 1),
            new Habit(LEARNING, JarCode.EDU, List.of("Fahasa", "Udemy", "Coursera"), 100, 600, 2),
            new Habit(SHOPPING, JarCode.PLAY, List.of("Shopee", "Lazada", "Uniqlo"), 150, 900, 2),
            new Habit(FUN, JarCode.PLAY, List.of("CGV", "Netflix", "Spotify"), 70, 300, 3),
            new Habit(GIVING, JarCode.GIVE, List.of("Quỹ Hy vọng", "Quà sinh nhật"), 200, 500, 1));

    private DemoDataPlan() {}

    /** Steps from the first day of the month MONTHS_BACK months ago up to yesterday (Vietnam time), oldest first. */
    static List<Step> plan(UUID userId, LocalDate today) {
        Random random = new Random(userId.getMostSignificantBits() ^ userId.getLeastSignificantBits());
        List<Step> wishes = new ArrayList<>();
        YearMonth first = YearMonth.from(today).minusMonths(MONTHS_BACK);
        for (YearMonth month = first; !month.isAfter(YearMonth.from(today)); month = month.plusMonths(1)) {
            wishes.addAll(
                    month(month, random, month.equals(YearMonth.from(today).minusMonths(1))));
        }
        List<Step> sorted = wishes.stream()
                .filter(step -> step.occurredAt().isBefore(startOf(today)))
                .sorted(Comparator.comparing(Step::occurredAt))
                .toList();
        return affordable(sorted);
    }

    private static List<Step> month(YearMonth month, Random random, boolean withShock) {
        List<Step> steps = new ArrayList<>();
        Instant payday = at(month.atDay(5), LocalTime.of(9, 0));
        steps.add(new Step(Kind.INCOME, payday, SALARY, null, SALARY_CATEGORY, "Công ty TNHH ABC"));
        // allocate the salary by target percentage, rounded down to a thousand VND; the rest stays unallocated
        for (DefaultJars.Spec spec : DefaultJars.SPECS) {
            if (spec.targetPct() != null) {
                BigDecimal share = SALARY.multiply(spec.targetPct())
                        .divide(new BigDecimal("100000"), 0, RoundingMode.DOWN)
                        .multiply(new BigDecimal("1000"));
                steps.add(new Step(Kind.ALLOCATE, payday.plusSeconds(300), share, spec.code(), null, null));
            }
        }
        steps.add(expense(
                at(month.atDay(6), LocalTime.of(8, 0)),
                "3500000",
                JarCode.NEC,
                HOME,
                "Tiền nhà tháng " + month.getMonthValue()));
        steps.add(expense(
                at(month.atDay(10), LocalTime.of(20, 0)), thousands(random, 400, 700), JarCode.NEC, HOME, "EVN HCMC"));
        steps.add(expense(at(month.atDay(12), LocalTime.of(20, 0)), "220000", JarCode.NEC, HOME, "Viettel Internet"));
        steps.add(expense(
                at(month.atDay(7), LocalTime.of(10, 0)), "1000000", JarCode.FFA, INVESTING, "SSI - chứng chỉ quỹ"));
        if (month.getMonthValue() % 2 == 0) {
            steps.add(new Step(
                    Kind.INCOME,
                    at(month.atDay(20), LocalTime.of(15, 0)),
                    new BigDecimal("2000000"),
                    null,
                    FREELANCE,
                    "Dự án freelance"));
        }
        for (Habit habit : HABITS) {
            for (int i = 0; i < habit.perMonth(); i++) {
                LocalDate day = month.atDay(1 + random.nextInt(month.lengthOfMonth()));
                Instant time = at(day, LocalTime.of(7 + random.nextInt(15), random.nextInt(60)));
                String merchant =
                        habit.merchants().get(random.nextInt(habit.merchants().size()));
                steps.add(expense(
                        time, thousands(random, habit.minK(), habit.maxK()), habit.jar(), habit.category(), merchant));
            }
        }
        if (withShock) {
            steps.add(
                    expense(at(month.atDay(18), LocalTime.of(11, 30)), "2500000", JarCode.NEC, HEALTH, "Bệnh viện FV"));
        }
        return steps;
    }

    /** Keeps an expense only if its jar holds enough at that moment; income and allocation always fit by design. */
    private static List<Step> affordable(List<Step> steps) {
        Map<JarCode, BigDecimal> balance = new EnumMap<>(JarCode.class);
        for (JarCode code : JarCode.values()) {
            balance.put(code, BigDecimal.ZERO);
        }
        List<Step> kept = new ArrayList<>();
        for (Step step : steps) {
            switch (step.kind()) {
                case INCOME -> balance.merge(JarCode.POOL, step.amount(), BigDecimal::add);
                case ALLOCATE -> {
                    if (balance.get(JarCode.POOL).compareTo(step.amount()) < 0) continue;
                    balance.merge(JarCode.POOL, step.amount().negate(), BigDecimal::add);
                    balance.merge(step.jar(), step.amount(), BigDecimal::add);
                }
                case EXPENSE -> {
                    if (balance.get(step.jar()).compareTo(step.amount()) < 0) continue;
                    balance.merge(step.jar(), step.amount().negate(), BigDecimal::add);
                }
            }
            kept.add(step);
        }
        return kept;
    }

    private static Step expense(Instant at, String amount, JarCode jar, UUID category, String counterparty) {
        return new Step(Kind.EXPENSE, at, new BigDecimal(amount), jar, category, counterparty);
    }

    private static String thousands(Random random, int minK, int maxK) {
        return (minK + random.nextInt(maxK - minK + 1)) + "000";
    }

    private static Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(BusinessTime.ZONE).toInstant();
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(BusinessTime.ZONE).toInstant();
    }

    private static UUID category(String suffix) {
        return UUID.fromString("01900000-0000-7000-8000-000000000" + suffix);
    }
}
