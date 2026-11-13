package io.github.thphuc06.jars.platform.audit;

import io.github.thphuc06.jars.shared.Uuids;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only code that touches {@code audit_log}: an insert and a read. There is deliberately no update and no delete
 * (the table also refuses both in the database, see V11).
 */
@Repository
class AuditStore {

    /** One stored row, as read back. */
    record Row(
            UUID id,
            Instant occurredAt,
            String actor,
            String action,
            String entityType,
            UUID entityId,
            String traceId,
            Map<String, Object> payload) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Joins the transaction of the business change; without one it refuses, so an audit row never commits alone. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            String actor,
            AuditAction action,
            UUID entityId,
            String traceId,
            Map<String, Object> payload,
            Instant occurredAt) {
        jdbc.sql("""
                        INSERT INTO audit_log (id, occurred_at, actor, action, entity_type, entity_id, trace_id, payload)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        """)
                .params(
                        Uuids.v7(),
                        utc(occurredAt),
                        actor,
                        action.name(),
                        action.entityType().name(),
                        entityId,
                        traceId,
                        json.writeValueAsString(payload))
                .update();
    }

    /**
     * The actor's own rows, newest first, strictly after the cursor in {@code (occurred_at DESC, id DESC)} order
     * (keyset, like the transaction list), served by {@code idx_audit_log_actor}. The actor is not optional: there is
     * no query for someone else's rows.
     */
    @Transactional(readOnly = true)
    public List<Row> page(
            String actor, AuditAction action, AuditEntityType entityType, Instant cursorAt, UUID cursorId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, occurred_at, actor, action, entity_type, entity_id, trace_id, payload::text AS payload
                FROM audit_log
                WHERE actor = :actor
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("actor", actor);
        if (action != null) {
            sql.append(" AND action = :action");
            params.put("action", action.name());
        }
        if (entityType != null) {
            sql.append(" AND entity_type = :entityType");
            params.put("entityType", entityType.name());
        }
        if (cursorAt != null) {
            sql.append(" AND (occurred_at, id) < (:cursorAt, :cursorId)");
            params.put("cursorAt", utc(cursorAt));
            params.put("cursorId", cursorId);
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT :limit");
        params.put("limit", limit);

        return jdbc.sql(sql.toString())
                .params(params)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class),
                        rs.getString("trace_id"),
                        payloadOf(rs.getString("payload"))))
                .list();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(String text) {
        return json.readValue(text, Map.class);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
