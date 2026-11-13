package io.github.thphuc06.jars.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The names are stored in {@code audit_log}, so they are a contract. Renaming one would split the history of an action
 * in two; this test makes that a conscious change (edit the list here, and add a migration if old rows must follow).
 */
class AuditActionTest {

    @Test
    void theStoredNamesDoNotChange() {
        assertThat(Arrays.stream(AuditAction.values()).map(a -> a + ":" + a.entityType()))
                .containsExactly(
                        "TRANSACTION_POSTED:TRANSACTION",
                        "JAR_TRANSFERRED:TRANSACTION",
                        "INCOME_ALLOCATED:TRANSACTION",
                        "TRANSACTION_RECATEGORIZED:TRANSACTION",
                        "JAR_CREATED:JAR",
                        "JAR_UPDATED:JAR",
                        "JAR_ARCHIVED:JAR",
                        "ALLOCATION_DECIDED:ALLOCATION_DECISION",
                        "MONTH_CLOSED:USER",
                        "GOAL_CREATED:GOAL",
                        "GOAL_UPDATED:GOAL",
                        "GOAL_ARCHIVED:GOAL",
                        "PROFILE_UPDATED:USER",
                        "RISK_PROFILE_SUBMITTED:RISK_ASSESSMENT",
                        "IMPORT_CREATED:STATEMENT_IMPORT",
                        "ANOMALY_FEEDBACK_GIVEN:ANOMALY");
        assertThat(Arrays.stream(AuditEntityType.values()).map(Enum::name))
                .containsExactly(
                        "TRANSACTION",
                        "JAR",
                        "GOAL",
                        "USER",
                        "RISK_ASSESSMENT",
                        "ALLOCATION_DECISION",
                        "STATEMENT_IMPORT",
                        "ANOMALY");
    }

    @Test
    void everyNameFitsTheColumnsAndLooksLikeACode() {
        for (AuditAction action : AuditAction.values()) {
            assertThat(action.name()).matches("[A-Z][A-Z_]{2,48}");
            assertThat(action.entityType()).isNotNull();
        }
    }
}
