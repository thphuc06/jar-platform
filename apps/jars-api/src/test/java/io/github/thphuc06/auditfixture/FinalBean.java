package io.github.thphuc06.auditfixture;

import io.github.thphuc06.jars.platform.audit.AuditAction;
import io.github.thphuc06.jars.platform.audit.Audited;
import org.springframework.stereotype.Service;

/** A final class cannot be subclassed, so CGLIB cannot proxy it. */
@Service
public final class FinalBean {

    @Audited(AuditAction.GOAL_CREATED)
    public void audited() {}
}
