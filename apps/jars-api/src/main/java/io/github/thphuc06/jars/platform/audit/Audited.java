package io.github.thphuc06.jars.platform.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a use case whose successful run is written to the audit log, inside the transaction of the change itself.
 *
 * <p>Where it may sit (a test enforces it, because a misplaced annotation does nothing and says nothing):
 *
 * <ul>
 *   <li>on a <b>public, non-final, non-static method of a Spring bean</b> that is a class: the aspect is a Spring AOP
 *       proxy (CGLIB), which cannot intercept anything else;
 *   <li>called from <b>another bean</b>, never as {@code this.method()}: a call on {@code this} does not pass the proxy;
 *   <li>on the method that runs <b>inside</b> the transaction and <b>inside</b> any retry loop, so a rolled back or
 *       retried attempt leaves no row, and where a replay of an idempotent request does not reach it (it is the
 *       {@code action} that {@code IdempotencyService.execute} runs, not the call that wraps it).
 * </ul>
 *
 * <p>The method describes what happened with {@link Audit#entity}; it throws if it does not, so a use case cannot be
 * audited without naming its entity.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    AuditAction value();
}
