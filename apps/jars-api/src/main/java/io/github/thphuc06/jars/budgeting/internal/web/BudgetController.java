package io.github.thphuc06.jars.budgeting.internal.web;

import io.github.thphuc06.jars.budgeting.internal.service.AllocationService;
import io.github.thphuc06.jars.budgeting.internal.service.AllocationService.AllocationResult;
import io.github.thphuc06.jars.budgeting.internal.service.AllocationService.Applied;
import io.github.thphuc06.jars.budgeting.internal.service.AllocationService.ApplyCommand;
import io.github.thphuc06.jars.budgeting.internal.service.AllocationSuggestionService;
import io.github.thphuc06.jars.budgeting.internal.service.AllocationSuggestionService.Suggestion;
import io.github.thphuc06.jars.budgeting.internal.service.PeriodQueryService;
import io.github.thphuc06.jars.budgeting.internal.service.PeriodQueryService.PeriodView;
import io.github.thphuc06.jars.ledger.IncomeAllocation;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.ProblemTypes;
import io.github.thphuc06.jars.shared.UserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/budget")
class BudgetController {

    private final AllocationSuggestionService suggestions;
    private final AllocationService allocations;
    private final PeriodQueryService periods;
    private final JsonMapper json;

    BudgetController(
            AllocationSuggestionService suggestions,
            AllocationService allocations,
            PeriodQueryService periods,
            JsonMapper json) {
        this.suggestions = suggestions;
        this.allocations = allocations;
        this.periods = periods;
        this.json = json;
    }

    /** How to divide an income over the active jars. Nothing is written. */
    @PostMapping("/allocation-suggestions")
    Suggestion suggest(UserId userId, @Valid @RequestBody SuggestionRequest request) {
        return suggestions.suggest(userId, new Money(request.amount(), Money.VND));
    }

    /** Applies a split of an income through the ledger and records the decision, once per Idempotency-Key. */
    @PostMapping("/allocations")
    ResponseEntity<AllocationResult> apply(
            UserId userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AllocationRequest request) {
        List<IncomeAllocation.Split> splits = request.splits() == null
                ? List.of()
                : request.splits().stream()
                        .map(s -> new IncomeAllocation.Split(s.jarId(), new Money(s.amount(), Money.VND)))
                        .sorted(Comparator.comparing(IncomeAllocation.Split::jarId))
                        .toList();
        ApplyCommand cmd = new ApplyCommand(
                new Money(request.amount(), Money.VND),
                splits,
                request.categoryId(),
                request.counterparty(),
                request.note(),
                request.occurredAt(),
                Boolean.TRUE.equals(request.skip()));
        // hash the normalised command (splits in a fixed order, amounts at two decimals), not the raw body
        String hash = IdempotencyService.hash("POST", "/api/v1/budget/allocations", json.writeValueAsString(cmd));
        Applied applied = allocations.apply(userId, idempotencyKey, hash, cmd);
        return ResponseEntity.status(201)
                .header("Idempotency-Replayed", String.valueOf(applied.replayed()))
                .body(applied.body());
    }

    /** Planned against spent for every active jar in one month ({@code YYYY-MM}). */
    @GetMapping("/periods/{month}")
    PeriodView period(UserId userId, @PathVariable String month) {
        return periods.period(userId, parse(month));
    }

    private static YearMonth parse(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new DomainException(ProblemTypes.BAD_REQUEST, "month phải có dạng YYYY-MM, ví dụ 2026-11.");
        }
    }

    record SuggestionRequest(
            @NotNull(message = "Thiếu số tiền")
            @Positive(message = "Số tiền phải lớn hơn 0")
            @Digits(integer = 17, fraction = 2, message = "Số tiền tối đa 2 chữ số thập phân")
            BigDecimal amount) {}

    record SplitRequest(
            @NotNull(message = "Thiếu hũ nhận tiền") UUID jarId,

            @NotNull(message = "Thiếu số tiền của hũ")
            @Positive(message = "Số tiền của mỗi hũ phải lớn hơn 0")
            @Digits(integer = 17, fraction = 2, message = "Số tiền tối đa 2 chữ số thập phân")
            BigDecimal amount) {}

    /**
     * @param splits what each jar receives; required unless {@code skip} is true, and must add up to {@code amount}
     * @param skip true to decline the suggestion: only the decision is recorded and nothing is posted
     */
    record AllocationRequest(
            @NotNull(message = "Thiếu số tiền")
            @Positive(message = "Số tiền phải lớn hơn 0")
            @Digits(integer = 17, fraction = 2, message = "Số tiền tối đa 2 chữ số thập phân")
            BigDecimal amount,

            @Valid @Size(max = 50, message = "Tối đa 50 hũ")
            List<@NotNull(message = "Thiếu phần chia") SplitRequest> splits,

            UUID categoryId,

            @Size(max = 120, message = "Nguồn thu tối đa 120 ký tự")
            String counterparty,

            @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
            String note,

            Instant occurredAt,
            Boolean skip) {}
}
