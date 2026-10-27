package io.github.thphuc06.jars.ledger.internal.web;

import com.fasterxml.jackson.annotation.JsonSetter;
import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.ledger.internal.JarCode;
import io.github.thphuc06.jars.ledger.internal.PostingService;
import io.github.thphuc06.jars.ledger.internal.TransactionDetails;
import io.github.thphuc06.jars.ledger.internal.TransactionQueries;
import io.github.thphuc06.jars.shared.VndAmount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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

@RestController
@RequestMapping("/api/v1/transactions")
class TransactionController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final IdentityApi identity;
    private final PostingService posting;
    private final TransactionQueries queries;

    TransactionController(IdentityApi identity, PostingService posting, TransactionQueries queries) {
        this.identity = identity;
        this.posting = posting;
        this.queries = queries;
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

    /** Money Inbox, newest first. Pass {@code nextCursor} of a page as {@code cursor} to get the following one. */
    @GetMapping
    TransactionPageResponse list(
            @RequestParam(defaultValue = "50") @Min(1) @Max(TransactionQueries.MAX_LIMIT) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID jarId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        TransactionQueries.Page page = queries.list(
                identity.currentUser().id(),
                new TransactionQueries.Filter(jarId, categoryId, type, from, to),
                cursor,
                limit);
        return new TransactionPageResponse(
                page.items().stream().map(TransactionItem::from).toList(), page.nextCursor());
    }

    @GetMapping("/{id}")
    TransactionItem get(@PathVariable UUID id) {
        return TransactionItem.from(queries.get(identity.currentUser().id(), id));
    }

    /**
     * Changes category, note and/or jar; amount and type never change (BR-2). Fields left out stay as they are; a
     * JSON {@code null} clears {@code categoryId} or {@code note}. Responds with the jars whose balance changed.
     */
    @PatchMapping("/{id}")
    PostingResponse amend(@PathVariable UUID id, @Valid @RequestBody AmendTransactionRequest request) {
        return PostingResponse.from(posting.amend(identity.currentUser().id(), id, request.toCommand()));
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

    /**
     * A class with setters, NOT a record: PATCH must tell "field not sent" (keep) apart from "field sent as null"
     * (clear). Jackson calls a setter only for properties present in the JSON, also when their value is null. With a
     * record it cannot: an absent {@code Optional} component arrives as {@code Optional.empty()}, exactly like an
     * explicit null (found by {@code patch_categoryAndNote_explicitNullClears_absentKeeps}).
     */
    static final class AmendTransactionRequest {

        private boolean categorySent;
        private UUID categoryId;

        private boolean noteSent;

        @Size(max = 500)
        private String note;

        private UUID jarId;

        @JsonSetter("categoryId")
        void categoryId(UUID value) {
            categorySent = true;
            categoryId = value;
        }

        @JsonSetter("note")
        void note(String value) {
            noteSent = true;
            note = value;
        }

        @JsonSetter("jarId")
        void jarId(UUID value) {
            jarId = value;
        }

        PostingService.Amendment toCommand() {
            return new PostingService.Amendment(
                    categorySent ? Optional.ofNullable(categoryId) : null,
                    noteSent ? Optional.ofNullable(note) : null,
                    jarId);
        }
    }

    record TransactionPageResponse(List<TransactionItem> items, String nextCursor) {}

    record TransactionItem(
            UUID id,
            TransactionType type,
            String amount,
            String currency,
            JarRef jar,
            JarRef toJar,
            CategoryRef category,
            String counterparty,
            String note,
            Instant occurredAt) {

        record JarRef(UUID id, JarCode code, String name) {

            static JarRef from(TransactionDetails.JarRef jar) {
                return jar == null ? null : new JarRef(jar.id(), jar.code(), jar.name());
            }
        }

        record CategoryRef(UUID id, String name) {

            static CategoryRef from(TransactionDetails.CategoryRef category) {
                return category == null ? null : new CategoryRef(category.id(), category.name());
            }
        }

        static TransactionItem from(TransactionDetails t) {
            return new TransactionItem(
                    t.id(),
                    t.type(),
                    MoneyJson.format(t.amount()),
                    t.currency(),
                    JarRef.from(t.jar()),
                    JarRef.from(t.toJar()),
                    CategoryRef.from(t.category()),
                    t.counterparty(),
                    t.note(),
                    t.occurredAt());
        }
    }
}
