package io.github.thphuc06.jars.platform.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inbox of an at-least-once consumer. Events can be delivered more than once (a listener that failed after doing
 * its work, a republished outbox row), so a consumer calls {@link #firstTime} as the first step of its transaction and
 * returns when the answer is false. Because the marker is written in the consumer's own transaction, "processed" and
 * "its work is committed" are always the same fact.
 */
@Service
public class ProcessedMessages {

    private final JdbcClient jdbc;

    ProcessedMessages(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether {@code consumer} already handled {@code messageId}. For work that is idempotent on its own and only wants
     * to skip a repeat early; a consumer that must not do the work twice still uses {@link #firstTime}.
     */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public boolean contains(String consumer, String messageId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM processed_messages WHERE consumer = ? AND message_id = ?)")
                .params(consumer, messageId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Records that {@code consumer} handles {@code messageId}. Returns true the first time and false for a repeat;
     * a concurrent duplicate waits for the first transaction and then gets false.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumer, String messageId) {
        return jdbc.sql("""
                        INSERT INTO processed_messages (consumer, message_id) VALUES (?, ?)
                        ON CONFLICT (consumer, message_id) DO NOTHING
                        """).params(consumer, messageId).update() == 1;
    }
}
