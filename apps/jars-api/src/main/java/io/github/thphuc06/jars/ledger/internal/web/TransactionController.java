package io.github.thphuc06.jars.ledger.internal.web;

import io.github.thphuc06.jars.ledger.internal.domain.TransactionType;
import io.github.thphuc06.jars.ledger.internal.service.LedgerCommandService;
import io.github.thphuc06.jars.ledger.internal.service.LedgerCommandService.PostTransactionCommand;
import io.github.thphuc06.jars.ledger.internal.service.LedgerQueryService;
import io.github.thphuc06.jars.ledger.internal.service.Views.CategoryView;
import io.github.thphuc06.jars.ledger.internal.service.Views.DailyBalanceView;
import io.github.thphuc06.jars.ledger.internal.service.Views.TransactionPage;
import io.github.thphuc06.jars.ledger.internal.service.Views.TransactionReceipt;
import io.github.thphuc06.jars.ledger.internal.service.Views.TransactionView;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1")
class TransactionController {

    private final LedgerQueryService queries;
    private final LedgerCommandService commands;
    private final JsonMapper json;

    TransactionController(LedgerQueryService queries, LedgerCommandService commands, JsonMapper json) {
        this.queries = queries;
        this.commands = commands;
        this.json = json;
    }

    @PostMapping("/transactions")
    ResponseEntity<TransactionReceipt> post(
            UserId userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PostTransactionRequest request) {
        PostTransactionCommand cmd = new PostTransactionCommand(
                request.type(),
                new Money(request.amount(), Money.VND),
                request.jarId(),
                request.categoryId(),
                request.counterparty(),
                request.note(),
                request.occurredAt());
        // hash the normalised command, not the raw body: "85000" and "85000.00" are the same request
        String hash = IdempotencyService.hash("POST", "/api/v1/transactions", json.writeValueAsString(cmd));
        return JarController.respond(commands.post(userId, idempotencyKey, hash, cmd));
    }

    @PatchMapping("/transactions/{id}")
    TransactionView recategorize(
            UserId userId, @PathVariable UUID id, @Valid @RequestBody PatchTransactionRequest request) {
        return commands.recategorize(userId, id, request.categoryId(), request.jarId());
    }

    @GetMapping("/transactions")
    TransactionPage list(
            UserId userId,
            @RequestParam(required = false) UUID jarId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return queries.transactions(userId, jarId, categoryId, type, from, to, limit, cursor);
    }

    @GetMapping("/categories")
    List<CategoryView> categories(UserId userId) {
        return queries.categories(userId);
    }

    @GetMapping("/balances/daily")
    List<DailyBalanceView> dailyBalances(UserId userId, @RequestParam(defaultValue = "30") int days) {
        return queries.dailyBalances(userId, days);
    }

    record PostTransactionRequest(
            @NotNull(message = "Thiếu loại giao dịch") TransactionType type,

            @NotNull(message = "Thiếu số tiền")
            @Positive(message = "Số tiền phải lớn hơn 0")
            @Digits(integer = 17, fraction = 2, message = "Số tiền tối đa 2 chữ số thập phân")
            BigDecimal amount,

            UUID jarId,
            UUID categoryId,

            @Size(max = 120, message = "Nơi nhận/nguồn tối đa 120 ký tự")
            String counterparty,

            @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
            String note,

            Instant occurredAt) {}

    record PatchTransactionRequest(UUID categoryId, UUID jarId) {}
}
