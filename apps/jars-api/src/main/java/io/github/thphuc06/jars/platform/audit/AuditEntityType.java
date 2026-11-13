package io.github.thphuc06.jars.platform.audit;

/**
 * What kind of thing an audit row is about ({@code audit_log.entity_type}). The names are stored, so they are a
 * contract: add new constants, never rename or reuse one (a test pins the list).
 */
public enum AuditEntityType {
    TRANSACTION,
    JAR,
    GOAL,
    USER,
    RISK_ASSESSMENT,
    ALLOCATION_DECISION,
    STATEMENT_IMPORT,
    ANOMALY
}
