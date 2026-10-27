package io.github.thphuc06.jars.ledger.internal.web;

import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.ledger.internal.DashboardQueries;
import io.github.thphuc06.jars.ledger.internal.JarCode;
import io.github.thphuc06.jars.shared.BusinessTime;
import io.github.thphuc06.jars.shared.MoneyFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class DashboardController {

    private final IdentityApi identity;
    private final DashboardQueries dashboards;
    private final Clock clock;

    DashboardController(IdentityApi identity, DashboardQueries dashboards, Clock clock) {
        this.identity = identity;
        this.dashboards = dashboards;
        this.clock = clock;
    }

    /** Everything the dashboard screen needs in ONE request; {@code month} defaults to the current Vietnamese month. */
    @GetMapping("/dashboard")
    DashboardResponse getDashboard(
            @RequestParam(required = false) @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be YYYY-MM")
                    String month) {
        YearMonth requested = month == null ? YearMonth.from(BusinessTime.today(clock)) : YearMonth.parse(month);
        return DashboardResponse.from(
                dashboards.dashboard(identity.currentUser().id(), requested));
    }

    /** Total balance at the end of each of the last {@code days} days (Vietnam time), oldest first, today last. */
    @GetMapping("/balances/daily")
    DailyBalancesResponse getDailyBalances(@RequestParam(defaultValue = "30") @Min(1) @Max(366) int days) {
        List<DailyBalanceItem> items =
                dashboards.dailyBalances(identity.currentUser().id(), BusinessTime.today(clock), days).stream()
                        .map(b -> new DailyBalanceItem(b.date(), MoneyFormat.format(b.balance())))
                        .toList();
        return new DailyBalancesResponse(items, "VND");
    }

    record DashboardResponse(
            String month,
            String currency,
            Balances balances,
            String income,
            String expense,
            String net,
            List<CategoryItem> byCategory,
            List<JarItem> byJar,
            List<TrendItem> trend) {

        record Balances(String total, String unallocated, String allocated) {}

        record CategoryItem(
                @Nullable UUID categoryId, @Nullable String name, String amount) {}

        record JarItem(UUID jarId, JarCode code, String name, String balance, String spent) {}

        record TrendItem(String month, String income, String expense) {}

        static DashboardResponse from(DashboardQueries.Dashboard d) {
            return new DashboardResponse(
                    d.month().toString(),
                    "VND",
                    new Balances(
                            MoneyFormat.format(d.total()),
                            MoneyFormat.format(d.unallocated()),
                            MoneyFormat.format(d.total().subtract(d.unallocated()))),
                    MoneyFormat.format(d.income()),
                    MoneyFormat.format(d.expense()),
                    MoneyFormat.format(d.income().subtract(d.expense())),
                    d.byCategory().stream()
                            .map(c -> new CategoryItem(c.categoryId(), c.name(), MoneyFormat.format(c.amount())))
                            .toList(),
                    d.byJar().stream()
                            .map(j -> new JarItem(
                                    j.jarId(),
                                    j.code(),
                                    j.name(),
                                    MoneyFormat.format(j.balance()),
                                    MoneyFormat.format(j.spent())))
                            .toList(),
                    d.trend().stream()
                            .map(t -> new TrendItem(
                                    t.month().toString(),
                                    MoneyFormat.format(t.income()),
                                    MoneyFormat.format(t.expense())))
                            .toList());
        }
    }

    record DailyBalancesResponse(List<DailyBalanceItem> items, String currency) {}

    record DailyBalanceItem(LocalDate date, String balance) {}
}
