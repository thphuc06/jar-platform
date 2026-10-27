package io.github.thphuc06.jars.ledger.internal.web;

import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.ledger.internal.PostingService;
import io.github.thphuc06.jars.shared.VndAmount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController {

    private final IdentityApi identity;
    private final PostingService posting;

    TransactionController(IdentityApi identity, PostingService posting) {
        this.identity = identity;
        this.posting = posting;
    }

    /** Records an INCOME (into POOL unless jarId is given) or an EXPENSE (from jarId, never POOL). BR-2. */
    @PostMapping
    ResponseEntity<PostingResponse> post(@Valid @RequestBody PostTransactionRequest request) {
        // the owner always comes from the access token, never from the request (IDOR protection)
        UUID userId = identity.currentUser().id();
        PostingService.Posting result = posting.post(userId, request.toCommand());
        return ResponseEntity.created(URI.create(
                        "/api/v1/transactions/" + result.transaction().id()))
                .body(PostingResponse.from(result));
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
