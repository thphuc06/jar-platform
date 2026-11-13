package io.github.thphuc06.jars.platform.audit;

import io.github.thphuc06.jars.platform.observability.MdcKeys;
import io.github.thphuc06.jars.platform.security.CurrentUser;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes one {@code audit_log} row for every {@link Audited} call that returns normally, in the transaction the call
 * runs in.
 *
 * <p><b>Order is the point.</b> Spring AOP builds one chain per bean method; the advisor with the lowest order value is
 * outermost. The transaction advisor is configured in {@link AuditConfig} with {@link #TRANSACTION_ORDER}, one below
 * this aspect's {@link #ORDER}, so for a method that is both {@code @Transactional} and {@code @Audited} the chain is
 *
 * <pre>
 * TransactionInterceptor  →  AuditAspect  →  method
 * </pre>
 *
 * The audit insert therefore happens before the commit, on the same connection: if the transaction rolls back (a
 * validation error later in the chain, an optimistic-lock conflict that surfaces at commit) the audit row goes with it,
 * and if the insert fails the exception rolls the business change back too. When the transaction is not a proxy but a
 * {@code TransactionTemplate} in the caller (the ledger), the audited bean is called inside it and the order does not
 * matter; either way the aspect refuses to run without an active read-write transaction instead of auditing outside
 * one.
 *
 * <p>A call that throws writes nothing. A call that returns but declares itself {@link Audit#unchanged()} (an idempotent
 * replay) writes nothing either.
 */
@Aspect
@Component
@Order(AuditAspect.ORDER)
class AuditAspect {

    /** Innermost: directly around the audited method, inside the transaction advisor. */
    static final int ORDER = Ordered.LOWEST_PRECEDENCE;

    /** The transaction advisor, one step outside {@link #ORDER}. Applied in {@link AuditConfig}. */
    static final int TRANSACTION_ORDER = ORDER - 1;

    /** The actor of work nobody asked for through the API: scheduled jobs, development seeding, tests. */
    static final String SYSTEM_ACTOR = "system";

    private final AuditStore store;
    private final CurrentUser currentUser;
    private final ObjectProvider<Tracer> tracer;
    private final Clock clock;

    AuditAspect(AuditStore store, CurrentUser currentUser, ObjectProvider<Tracer> tracer, Clock clock) {
        this.store = store;
        this.currentUser = currentUser;
        this.tracer = tracer;
        this.clock = clock;
    }

    @Around("@annotation(audited)")
    Object audit(ProceedingJoinPoint call, Audited audited) throws Throwable {
        AuditAction action = audited.value();
        requireTransaction(call, action);

        AuditEntry entry = Audit.open();
        Object result;
        try {
            result = call.proceed();
        } finally {
            Audit.close(entry);
        }
        if (entry.isUnchanged()) {
            return result;
        }
        if (entry.entityId() == null) {
            throw new IllegalStateException(
                    call.getSignature().toShortString() + " is @Audited(" + action + ") but did not call Audit.entity");
        }
        store.append(
                currentUser.find().map(Object::toString).orElse(SYSTEM_ACTOR),
                action,
                entry.entityId(),
                traceId(),
                entry.facts(),
                now());
        return result;
    }

    private static void requireTransaction(ProceedingJoinPoint call, AuditAction action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    call.getSignature().toShortString() + " is @Audited(" + action
                            + ") but runs without a read-write transaction. Put @Audited on a method that runs inside the "
                            + "transaction of the change (a @Transactional method, or a bean called from a TransactionTemplate).");
        }
    }

    /** The id the logs carry (the MDC value), or the id of the current span for work that has no request filter. */
    private String traceId() {
        String fromMdc = MDC.get(MdcKeys.TRACE_ID);
        if (fromMdc != null) {
            return fromMdc;
        }
        Tracer t = tracer.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        if (span == null) {
            return null;
        }
        String id = span.context().traceId();
        return id == null || id.length() != 32 ? null : id; // a no-op tracer reports empty ids
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
