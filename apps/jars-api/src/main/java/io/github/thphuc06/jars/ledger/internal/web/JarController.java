package io.github.thphuc06.jars.ledger.internal.web;

import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.ledger.internal.JarCode;
import io.github.thphuc06.jars.ledger.internal.JarView;
import io.github.thphuc06.jars.ledger.internal.LedgerQueries;
import io.github.thphuc06.jars.ledger.internal.PostingService;
import io.github.thphuc06.jars.shared.MoneyFormat;
import io.github.thphuc06.jars.shared.VndAmount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jars")
class JarController {

    private final IdentityApi identity;
    private final LedgerQueries queries;
    private final PostingService posting;

    JarController(IdentityApi identity, LedgerQueries queries, PostingService posting) {
        this.identity = identity;
        this.queries = queries;
        this.posting = posting;
    }

    /** The jars of the caller (POOL hidden, reported as {@code unallocated}) with balances. */
    @GetMapping
    JarsResponse listJars() {
        return JarsResponse.from(queries.jarsOf(identity.currentUser().id()));
    }

    /** Moves money between two jars of the caller; {@code fromJarId} = POOL allocates income. Idempotency-Key required. */
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<PostingResponse> transferBetweenJars(
            @RequestHeader(TransactionController.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return PostingResponse.created(
                posting.transfer(identity.currentUser().id(), idempotencyKey, request.toCommand()));
    }

    record TransferRequest(
            @NotNull UUID fromJarId,
            @NotNull UUID toJarId,
            @NotNull @VndAmount BigDecimal amount,
            @Size(max = 500) @Nullable String note,
            @Nullable Instant occurredAt) {

        PostingService.Transfer toCommand() {
            return new PostingService.Transfer(fromJarId, toJarId, amount, note, occurredAt);
        }
    }

    record JarsResponse(List<JarResponse> jars, String unallocated, String total, String currency) {

        static JarsResponse from(LedgerQueries.JarsOverview overview) {
            return new JarsResponse(
                    overview.jars().stream().map(JarResponse::from).toList(),
                    MoneyFormat.format(overview.unallocated()),
                    MoneyFormat.format(overview.total()),
                    overview.currency());
        }
    }

    record JarResponse(UUID id, JarCode code, String name, BigDecimal targetPct, String balance) {

        static JarResponse from(JarView jar) {
            return new JarResponse(
                    jar.id(), jar.code(), jar.name(), jar.targetPct(), MoneyFormat.format(jar.balance()));
        }
    }
}
