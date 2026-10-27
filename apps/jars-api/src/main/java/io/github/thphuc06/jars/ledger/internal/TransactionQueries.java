package io.github.thphuc06.jars.ledger.internal;

import io.github.thphuc06.jars.ledger.TransactionType;
import io.github.thphuc06.jars.shared.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Money Inbox: GET /transactions (keyset pagination, filters) and GET /transactions/{id}. */
@Service
public class TransactionQueries {

    public static final int MAX_LIMIT = 100;

    /** JPA hint: load these associations eagerly for this query only (the mapping stays LAZY everywhere else). */
    private static final String FETCH_GRAPH = "jakarta.persistence.fetchgraph";

    private final EntityManager entityManager;

    TransactionQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Every field is optional; {@code jarId} also matches transfers INTO that jar; {@code to} is exclusive. */
    public record Filter(
            @Nullable UUID jarId,
            @Nullable UUID categoryId,
            @Nullable TransactionType type,
            @Nullable Instant from,
            @Nullable Instant to) {}

    public record Page(
            List<TransactionDetails> items, @Nullable String nextCursor) {}

    /**
     * Newest first. The JPQL is assembled only from constant fragments; every value is a bound parameter, so the
     * dynamic filters cannot inject SQL. One SELECT per page: the entity graph joins jars and category (no N+1), and
     * {@code limit + 1} rows tell whether a next page exists without a {@code COUNT(*)}.
     */
    @Transactional(readOnly = true)
    public Page list(UUID userId, Filter filter, @Nullable String cursor, int limit) {
        StringBuilder jpql = new StringBuilder("SELECT t FROM LedgerTransaction t WHERE t.userId = :userId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);
        if (filter.jarId() != null) {
            jpql.append(" AND (t.jarId = :jarId OR t.toJarId = :jarId)");
            params.put("jarId", filter.jarId());
        }
        if (filter.categoryId() != null) {
            jpql.append(" AND t.categoryId = :categoryId");
            params.put("categoryId", filter.categoryId());
        }
        if (filter.type() != null) {
            jpql.append(" AND t.type = :type");
            params.put("type", filter.type());
        }
        if (filter.from() != null) {
            jpql.append(" AND t.occurredAt >= :from");
            params.put("from", filter.from());
        }
        if (filter.to() != null) {
            jpql.append(" AND t.occurredAt < :to");
            params.put("to", filter.to());
        }
        if (cursor != null) {
            PageCursor after = PageCursor.decode(cursor);
            // row-value comparison = "(occurredAt < x) OR (occurredAt = x AND id < y)", answered by the index
            jpql.append(" AND (t.occurredAt, t.id) < (:cursorAt, :cursorId)");
            params.put("cursorAt", after.occurredAt());
            params.put("cursorId", after.id());
        }
        jpql.append(" ORDER BY t.occurredAt DESC, t.id DESC");

        TypedQuery<LedgerTransaction> query = entityManager.createQuery(jpql.toString(), LedgerTransaction.class);
        params.forEach(query::setParameter);
        query.setHint(FETCH_GRAPH, entityManager.getEntityGraph(LedgerTransaction.WITH_REFERENCES));
        query.setMaxResults(limit + 1);
        List<LedgerTransaction> rows = query.getResultList();

        boolean hasNext = rows.size() > limit;
        List<LedgerTransaction> page = hasNext ? rows.subList(0, limit) : rows;
        String nextCursor = hasNext ? PageCursor.after(page.getLast()).encode() : null;
        return new Page(page.stream().map(TransactionDetails::of).toList(), nextCursor);
    }

    /** One transaction of the caller; someone else's is "not found" (IDOR protection). */
    @Transactional(readOnly = true)
    public TransactionDetails get(UUID userId, UUID transactionId) {
        return entityManager
                .createQuery(
                        "SELECT t FROM LedgerTransaction t WHERE t.id = :id AND t.userId = :userId",
                        LedgerTransaction.class)
                .setParameter("id", transactionId)
                .setParameter("userId", userId)
                .setHint(FETCH_GRAPH, entityManager.getEntityGraph(LedgerTransaction.WITH_REFERENCES))
                .getResultStream()
                .findFirst()
                .map(TransactionDetails::of)
                .orElseThrow(() -> new NotFoundException("transaction", transactionId));
    }
}
