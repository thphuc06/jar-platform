package io.github.thphuc06.jars.ledger.internal.web;

import io.github.thphuc06.jars.ledger.internal.service.LedgerCommandService;
import io.github.thphuc06.jars.ledger.internal.service.LedgerCommandService.TransferCommand;
import io.github.thphuc06.jars.ledger.internal.service.LedgerQueryService;
import io.github.thphuc06.jars.ledger.internal.service.Views.JarView;
import io.github.thphuc06.jars.ledger.internal.service.Views.TransactionReceipt;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService;
import io.github.thphuc06.jars.platform.idempotency.IdempotencyService.Result;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/jars")
class JarController {

    private final LedgerQueryService queries;
    private final LedgerCommandService commands;
    private final JsonMapper json;

    JarController(LedgerQueryService queries, LedgerCommandService commands, JsonMapper json) {
        this.queries = queries;
        this.commands = commands;
        this.json = json;
    }

    @GetMapping
    List<JarView> list(UserId userId) {
        return queries.jars(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    JarView create(UserId userId, @Valid @RequestBody CreateJarRequest request) {
        return commands.createJar(userId, request.name(), request.targetPct());
    }

    @PatchMapping("/{id}")
    JarView update(UserId userId, @PathVariable UUID id, @Valid @RequestBody UpdateJarRequest request) {
        return commands.updateJar(userId, id, request.name(), request.targetPct());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(UserId userId, @PathVariable UUID id) {
        commands.archiveJar(userId, id);
    }

    @PostMapping("/transfers")
    ResponseEntity<TransactionReceipt> transfer(
            UserId userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferCommand cmd = new TransferCommand(
                request.fromJarId(), request.toJarId(), new Money(request.amount(), Money.VND), request.note());
        String hash = IdempotencyService.hash("POST", "/api/v1/jars/transfers", json.writeValueAsString(cmd));
        return respond(commands.transfer(userId, idempotencyKey, hash, cmd));
    }

    static ResponseEntity<TransactionReceipt> respond(Result<TransactionReceipt> result) {
        return ResponseEntity.status(result.status())
                .header("Idempotency-Replayed", String.valueOf(result.replayed()))
                .body(result.body());
    }

    record CreateJarRequest(
            @NotBlank(message = "Tên hũ không được để trống") @Size(max = 40, message = "Tên hũ tối đa 40 ký tự")
            String name,

            @DecimalMin(value = "0", message = "Tỷ lệ phải từ 0")
            @DecimalMax(value = "100", message = "Tỷ lệ tối đa 100")
            @Digits(integer = 3, fraction = 2, message = "Tỷ lệ tối đa 2 chữ số thập phân")
            BigDecimal targetPct) {}

    record UpdateJarRequest(
            @Size(min = 1, max = 40, message = "Tên hũ từ 1 đến 40 ký tự")
            String name,

            @DecimalMin(value = "0", message = "Tỷ lệ phải từ 0")
            @DecimalMax(value = "100", message = "Tỷ lệ tối đa 100")
            @Digits(integer = 3, fraction = 2, message = "Tỷ lệ tối đa 2 chữ số thập phân")
            BigDecimal targetPct) {}

    record TransferRequest(
            @NotNull(message = "Thiếu hũ nguồn") UUID fromJarId,
            @NotNull(message = "Thiếu hũ đích") UUID toJarId,

            @NotNull(message = "Thiếu số tiền")
            @Positive(message = "Số tiền phải lớn hơn 0")
            @Digits(integer = 17, fraction = 2, message = "Số tiền tối đa 2 chữ số thập phân")
            BigDecimal amount,

            @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
            String note) {}
}
