package io.github.thphuc06.jars.ledger.internal.web;

import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.ledger.internal.Idempotent;
import io.github.thphuc06.jars.ledger.internal.JarCode;
import io.github.thphuc06.jars.ledger.internal.JarView;
import io.github.thphuc06.jars.ledger.internal.PostingService.Posting;
import io.github.thphuc06.jars.ledger.internal.TransactionView;
import io.github.thphuc06.jars.shared.MoneyFormat;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

/** Body of 201 responses that move money: the transaction plus the new balance of every jar it touched. */
record PostingResponse(TransactionResponse transaction, List<JarBalance> jars) {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    record TransactionResponse(
            UUID id,
            TransactionType type,
            String amount,
            String currency,
            UUID jarId,
            UUID toJarId,
            UUID categoryId,
            String counterparty,
            String note,
            Instant occurredAt) {

        static TransactionResponse from(TransactionView t) {
            return new TransactionResponse(
                    t.id(),
                    t.type(),
                    MoneyFormat.format(t.amount()),
                    t.currency(),
                    t.jarId(),
                    t.toJarId(),
                    t.categoryId(),
                    t.counterparty(),
                    t.note(),
                    t.occurredAt());
        }
    }

    record JarBalance(UUID id, JarCode code, String balance) {

        static JarBalance from(JarView jar) {
            return new JarBalance(jar.id(), jar.code(), MoneyFormat.format(jar.balance()));
        }
    }

    static PostingResponse from(Posting posting) {
        return new PostingResponse(
                TransactionResponse.from(posting.transaction()),
                posting.jars().stream().map(JarBalance::from).toList());
    }

    /**
     * 201 + Location, identical for the first request and its repetitions (same body: the stored result). A repetition
     * is marked with {@code Idempotent-Replayed: true} so the client and the logs can tell them apart.
     */
    static ResponseEntity<PostingResponse> created(Idempotent<Posting> result) {
        Posting posting = result.value();
        ResponseEntity.BodyBuilder response = ResponseEntity.created(
                URI.create("/api/v1/transactions/" + posting.transaction().id()));
        if (result.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(from(posting));
    }
}
