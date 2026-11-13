package io.github.thphuc06.jars.platform.audit;

import static io.github.thphuc06.jars.platform.audit.AuditEntityType.ALLOCATION_DECISION;
import static io.github.thphuc06.jars.platform.audit.AuditEntityType.ANOMALY;
import static io.github.thphuc06.jars.platform.audit.AuditEntityType.GOAL;
import static io.github.thphuc06.jars.platform.audit.AuditEntityType.JAR;
import static io.github.thphuc06.jars.platform.audit.AuditEntityType.RISK_ASSESSMENT;
import static io.github.thphuc06.jars.platform.audit.AuditEntityType.STATEMENT_IMPORT;
import static io.github.thphuc06.jars.platform.audit.AuditEntityType.TRANSACTION;
import static io.github.thphuc06.jars.platform.audit.AuditEntityType.USER;

/**
 * Every state change that is recorded in the audit log ({@code audit_log.action}), with the kind of entity it is
 * about. One place lists the whole vocabulary, so a reviewer sees at once what is (and is not) audited. The names are
 * stored: add constants, never rename or reuse one (a test pins the list).
 */
public enum AuditAction {
    TRANSACTION_POSTED(TRANSACTION),
    JAR_TRANSFERRED(TRANSACTION),
    INCOME_ALLOCATED(TRANSACTION),
    TRANSACTION_RECATEGORIZED(TRANSACTION),
    JAR_CREATED(JAR),
    JAR_UPDATED(JAR),
    JAR_ARCHIVED(JAR),
    ALLOCATION_DECIDED(ALLOCATION_DECISION),
    MONTH_CLOSED(USER),
    GOAL_CREATED(GOAL),
    GOAL_UPDATED(GOAL),
    GOAL_ARCHIVED(GOAL),
    PROFILE_UPDATED(USER),
    RISK_PROFILE_SUBMITTED(RISK_ASSESSMENT),
    IMPORT_CREATED(STATEMENT_IMPORT),
    ANOMALY_FEEDBACK_GIVEN(ANOMALY);

    private final AuditEntityType entityType;

    AuditAction(AuditEntityType entityType) {
        this.entityType = entityType;
    }

    public AuditEntityType entityType() {
        return entityType;
    }
}
