package io.github.thphuc06.jars.ledger.internal.web;

import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.ledger.internal.JarCode;
import io.github.thphuc06.jars.ledger.internal.JarView;
import io.github.thphuc06.jars.ledger.internal.PostingService.Posting;
import io.github.thphuc06.jars.ledger.internal.TransactionView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Body of 201 responses that move money: the transaction plus the new balance of every jar it touched. */
record PostingResponse(TransactionResponse transaction, List<JarBalance> jars) {

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
                    MoneyJson.format(t.amount()),
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
            return new JarBalance(jar.id(), jar.code(), MoneyJson.format(jar.balance()));
        }
    }

    static PostingResponse from(Posting posting) {
        return new PostingResponse(
                TransactionResponse.from(posting.transaction()),
                posting.jars().stream().map(JarBalance::from).toList());
    }
}
