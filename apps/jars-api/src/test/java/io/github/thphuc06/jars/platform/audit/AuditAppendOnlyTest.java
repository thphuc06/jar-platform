package io.github.thphuc06.jars.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thphuc06.jars.ApiTestSupport;
import io.github.thphuc06.jars.shared.Uuids;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * The table refuses to be rewritten, whoever asks (V11): the application has no update or delete for it, and the
 * database says no to the one who tries anyway. Inserting stays possible, which is all the aspect needs.
 */
class AuditAppendOnlyTest extends ApiTestSupport {

    private UUID insertRow() {
        UUID id = Uuids.v7();
        jdbc.sql("""
                        INSERT INTO audit_log (id, occurred_at, actor, action, entity_type, entity_id, trace_id, payload)
                        VALUES (?, ?, 'append-only-test', 'GOAL_CREATED', 'GOAL', ?, 'abc', '{"k":"v"}'::jsonb)
                        """)
                .params(id, OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC), UUID.randomUUID())
                .update();
        return id;
    }

    private String actionOf(UUID id) {
        return jdbc.sql("SELECT action FROM audit_log WHERE id = ?")
                .param(id)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    @Test
    void insert_works() {
        UUID id = insertRow();

        assertThat(actionOf(id)).isEqualTo("GOAL_CREATED");
    }

    @Test
    void update_isRefusedByTheDatabase() {
        UUID id = insertRow();

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_log SET action = 'JAR_ARCHIVED' WHERE id = ?")
                        .param(id)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log is append-only: UPDATE");
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_log SET payload = '{}'::jsonb WHERE actor = 'append-only-test'")
                        .update())
                .hasMessageContaining("append-only");

        assertThat(actionOf(id)).isEqualTo("GOAL_CREATED");
    }

    @Test
    void delete_isRefusedByTheDatabase() {
        UUID id = insertRow();

        assertThatThrownBy(() ->
                        jdbc.sql("DELETE FROM audit_log WHERE id = ?").param(id).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log is append-only: DELETE");

        assertThat(actionOf(id)).isEqualTo("GOAL_CREATED");
    }

    @Test
    void truncate_isRefusedByTheDatabase() {
        UUID id = insertRow();

        assertThatThrownBy(() -> jdbc.sql("TRUNCATE audit_log").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log is append-only: TRUNCATE");

        assertThat(actionOf(id)).isEqualTo("GOAL_CREATED");
    }

    @Test
    void thePayloadMustBeAJsonObject() {
        assertThatThrownBy(() ->
                        jdbc.sql("""
                                INSERT INTO audit_log (id, occurred_at, actor, action, entity_type, entity_id, payload)
                                VALUES (?, now(), 'append-only-test', 'GOAL_CREATED', 'GOAL', ?, '[1,2]'::jsonb)
                                """).params(Uuids.v7(), UUID.randomUUID()).update())
                .hasMessageContaining("ck_audit_log_payload_object");
    }
}
