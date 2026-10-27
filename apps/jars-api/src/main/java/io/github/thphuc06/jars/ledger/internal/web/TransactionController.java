package io.github.thphuc06.jars.ledger.internal.web;

import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.ledger.internal.PostingService;
import io.github.thphuc06.jars.shared.VndAmount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final IdentityApi identity;
    private final PostingService posting;

    TransactionController(IdentityApi identity, PostingService posting) {
        this.identity = identity;
        this.posting = posting;
    }

    /**
     * Records an INCOME (into POOL unless jarId is given) or an EXPENSE (from jarId, never POOL). BR-2. The
     * Idempotency-Key header is required: re-sending the same request with the same key never records it twice.
     */
    @PostMapping
    ResponseEntity<PostingResponse> post(
            @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey, @Valid @RequestBody PostTransactionRequest request) {
        // the owner always comes from the access token, never from the request (IDOR protection)
        UUID userId = identity.currentUser().id();
        return PostingResponse.created(posting.post(userId, idempotencyKey, request.toCommand()));
    }

    record PostTransactionRequest(
            @NotNull TransactionType type,
            @NotNull @VndAmount BigDecimal amount,
            UUID jarId,
            UUID categoryId,
            @Size(max = 200) String counterparty,
            @Size(max = 500) String note,
            Instant occurredAt) {

        PostingService.PostTransaction toCommand() {
            return new PostingService.PostTransaction(type, amount, jarId, categoryId, counterparty, note, occurredAt);
        }
    }
}
