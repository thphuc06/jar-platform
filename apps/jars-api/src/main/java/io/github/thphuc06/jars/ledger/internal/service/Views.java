package io.github.thphuc06.jars.ledger.internal.service;

import io.github.thphuc06.jars.ledger.internal.domain.Category;
import io.github.thphuc06.jars.ledger.internal.domain.Jar;
import io.github.thphuc06.jars.ledger.internal.domain.LedgerTransaction;
import io.github.thphuc06.jars.ledger.internal.persistence.LedgerQueries;
import io.github.thphuc06.jars.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** Read models returned by the ledger services. They are plain records so they can also be stored as JSON. */
public final class Views {

    private Views() {}

    public record JarView(UUID id, String code, String name, BigDecimal targetPct, Money balance) {
        static JarView of(Jar jar) {
            return new JarView(jar.getId(), jar.getCode().name(), jar.getName(), jar.getTargetPct(), jar.balance());
        }
    }

    public record CategoryView(UUID id, String name, String kind, UUID parentId, boolean system) {
        static CategoryView of(Category c) {
            return new CategoryView(c.getId(), c.getName(), c.getKind().name(), c.getParentId(), c.isSystem());
        }
    }

    /** A transaction right after it was written. */
    public record TransactionView(
            UUID id,
            String type,
            Money amount,
            UUID jarId,
            UUID toJarId,
            UUID categoryId,
            String counterparty,
            String note,
            Instant occurredAt,
            String source) {
        static TransactionView of(LedgerTransaction t) {
            return new TransactionView(
                    t.getId(),
                    t.getType().name(),
                    t.money(),
                    t.getJarId(),
                    t.getToJarId(),
                    t.getCategoryId(),
                    t.getCounterparty(),
                    t.getNote(),
                    t.getOccurredAt(),
                    t.getSource().name());
        }
    }

    public record JarBalance(UUID jarId, Money balance) {}

    /** The response of a posting: the transaction and the new balance of every jar it touched. */
    public record TransactionReceipt(TransactionView transaction, List<JarBalance> jarBalances) {}

    /** A row of the Money Inbox, with jar and category names already joined in. */
    public record TransactionListItem(
            UUID id,
            String type,
            Money amount,
            UUID jarId,
            String jarName,
            UUID toJarId,
            String toJarName,
            UUID categoryId,
            String categoryName,
            String counterparty,
            String note,
            Instant occurredAt,
            String source) {
        static TransactionListItem of(LedgerQueries.TransactionRow r) {
            return new TransactionListItem(
                    r.id(),
                    r.type(),
                    new Money(r.amount(), Currency.getInstance(r.currency())),
                    r.jarId(),
                    r.jarName(),
                    r.toJarId(),
                    r.toJarName(),
                    r.categoryId(),
                    r.categoryName(),
                    r.counterparty(),
                    r.note(),
                    r.occurredAt(),
                    r.source());
        }
    }

    public record TransactionPage(List<TransactionListItem> items, String nextCursor) {}

    public record DailyBalanceView(java.time.LocalDate date, Money balance) {}
}
