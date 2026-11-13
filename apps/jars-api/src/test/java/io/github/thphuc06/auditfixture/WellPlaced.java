package io.github.thphuc06.auditfixture;

import io.github.thphuc06.jars.platform.audit.AuditAction;
import io.github.thphuc06.jars.platform.audit.Audited;
import org.springframework.stereotype.Service;

/** The right way: a public method of a Spring bean, called from another bean. */
@Service
public class WellPlaced {

    @Audited(AuditAction.GOAL_CREATED)
    public void audited() {}

    public void notAudited() {}

    /** A stereotype that is not @Service still makes a bean. */
    @org.springframework.stereotype.Repository
    public static class AlsoABean {

        @Audited(AuditAction.GOAL_CREATED)
        public void audited() {}
    }
}
