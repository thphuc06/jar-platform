package io.github.thphuc06.auditfixture;

import io.github.thphuc06.jars.platform.audit.AuditAction;
import io.github.thphuc06.jars.platform.audit.Audited;
import org.springframework.stereotype.Service;

/**
 * Every way to put {@code @Audited} where the aspect cannot reach it. Not part of the application (the package is
 * outside {@code io.github.thphuc06.jars}); only {@code AuditedPlacementTest} reads these classes, to prove that each
 * rule fires on the mistake it is meant to catch.
 */
@Service
public class MisplacedAudited {

    @Audited(AuditAction.GOAL_CREATED)
    void packagePrivate() {}

    @Audited(AuditAction.GOAL_CREATED)
    private void hidden() {}

    @Audited(AuditAction.GOAL_CREATED)
    public final void finalMethod() {}

    @Audited(AuditAction.GOAL_CREATED)
    public static void staticMethod() {}

    @Audited(AuditAction.GOAL_CREATED)
    public void callee() {}

    /** The call that bypasses the proxy. */
    public void caller() {
        callee();
    }

    private void unused() {
        hidden();
    }
}
