package io.github.thphuc06.jars.advisor.internal.persistence;

import io.github.thphuc06.jars.shared.Uuids;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Chat sessions and messages. Plain JDBC: two small tables, read in one order, never updated in place. */
@Repository
public class ChatStore {

    public record Session(UUID id, String title, Instant createdAt, Instant updatedAt) {}

    public record Message(
            UUID id,
            String role,
            String content,
            Integer inputTokens,
            Integer outputTokens,
            String guardrail,
            Instant createdAt) {}

    private final JdbcClient jdbc;

    ChatStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Session createSession(UUID userId, String title, Instant now) {
        UUID id = Uuids.v7();
        jdbc.sql("INSERT INTO chat_sessions (id, user_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)")
                .params(id, userId, title, ts(now), ts(now))
                .update();
        return new Session(id, title, now, now);
    }

    /** Always with the user: someone else's session is "not found" (anti-IDOR). */
    public Optional<Session> session(UUID userId, UUID sessionId) {
        return jdbc.sql("SELECT id, title, created_at, updated_at FROM chat_sessions WHERE id = ? AND user_id = ?")
                .params(sessionId, userId)
                .query((rs, n) -> new Session(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    public List<Session> sessions(UUID userId, int limit) {
        return jdbc.sql("""
                        SELECT id, title, created_at, updated_at FROM chat_sessions
                        WHERE user_id = ? ORDER BY updated_at DESC, id DESC LIMIT ?
                        """)
                .params(userId, limit)
                .query((rs, n) -> new Session(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    /** The last {@code limit} messages of a session, oldest first. */
    public List<Message> lastMessages(UUID sessionId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM (
                            SELECT id, role, content, input_tokens, output_tokens, guardrail, created_at
                            FROM chat_messages WHERE session_id = ? ORDER BY id DESC LIMIT ?
                        ) last ORDER BY id
                        """)
                .params(sessionId, limit)
                .query((rs, n) -> new Message(
                        rs.getObject("id", UUID.class),
                        rs.getString("role"),
                        rs.getString("content"),
                        (Integer) rs.getObject("input_tokens"),
                        (Integer) rs.getObject("output_tokens"),
                        rs.getString("guardrail"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Transactional
    public Message append(
            UUID sessionId,
            String role,
            String content,
            Integer inputTokens,
            Integer outputTokens,
            String guardrail,
            Instant now) {
        UUID id = Uuids.v7();
        jdbc.sql("""
                        INSERT INTO chat_messages
                            (id, session_id, role, content, input_tokens, output_tokens, guardrail, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(id, sessionId, role, content, inputTokens, outputTokens, guardrail, ts(now))
                .update();
        jdbc.sql("UPDATE chat_sessions SET updated_at = ? WHERE id = ?")
                .params(ts(now), sessionId)
                .update();
        return new Message(id, role, content, inputTokens, outputTokens, guardrail, now);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
