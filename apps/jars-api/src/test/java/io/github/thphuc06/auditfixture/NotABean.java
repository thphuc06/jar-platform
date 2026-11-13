package io.github.thphuc06.auditfixture;

import io.github.thphuc06.jars.platform.audit.AuditAction;
import io.github.thphuc06.jars.platform.audit.Audited;

/** A plain class: nothing creates a proxy for it, so the annotation would do nothing. */
public class NotABean {

    @Audited(AuditAction.GOAL_CREATED)
    public void looksAudited() {}
}
