package io.github.thphuc06.jars.imports.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The rows an import skipped and why (plain JDBC: there is nothing to map but three columns). */
@Repository
public class ImportErrorStore {

    public record RowError(int line, String reason) {}

    private final JdbcClient jdbc;

    ImportErrorStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Joins the caller's transaction (the batch chunk); a row that is already recorded keeps its first reason. */
    public void record(UUID importId, int line, String reason) {
        jdbc.sql("""
                        INSERT INTO statement_import_errors (import_id, line_number, reason)
                        VALUES (?, ?, ?)
                        ON CONFLICT (import_id, line_number) DO NOTHING
                        """).params(importId, line, reason).update();
    }

    public int count(UUID importId) {
        return jdbc.sql("SELECT count(*) FROM statement_import_errors WHERE import_id = ?")
                .param(importId)
                .query(Integer.class)
                .single();
    }

    public List<RowError> firstErrors(UUID importId, int limit) {
        return jdbc.sql("""
                        SELECT line_number, reason FROM statement_import_errors
                        WHERE import_id = ? ORDER BY line_number LIMIT ?
                        """)
                .params(importId, limit)
                .query((rs, n) -> new RowError(rs.getInt("line_number"), rs.getString("reason")))
                .list();
    }
}
