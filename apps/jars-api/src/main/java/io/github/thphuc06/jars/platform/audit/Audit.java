package io.github.thphuc06.jars.platform.audit;

import java.util.UUID;

/**
 * How the body of an {@link Audited} method says what it did. The aspect opens a frame around the call; the method
 * fills it with {@link #entity} (the id of what it created or changed, plus a few facts) or calls {@link #unchanged}
 * when the call turned out to change nothing (an idempotent repeat), and the aspect writes the row after the method
 * returns.
 *
 * <p>A thread-bound frame instead of a SpEL expression or an interface on the result: the id and the facts are plain
 * typed Java next to the code that knows them, nothing is looked up by reflection, and a method that runs without a
 * frame (annotation on a bean the proxy does not reach, a call on {@code this}) fails loudly on its first call here
 * rather than silently skipping the audit.
 */
public final class Audit {

    private static final ThreadLocal<AuditEntry> CURRENT = new ThreadLocal<>();

    private Audit() {}

    /**
     * Names the entity this call created or changed.
     *
     * @throws IllegalStateException outside an {@link Audited} call, which means the aspect did not run
     */
    public static AuditEntry entity(UUID id) {
        AuditEntry frame = frame();
        frame.entity(id);
        return frame;
    }

    /** The call changed nothing (for instance the replay of an already processed request): no row is written. */
    public static void unchanged() {
        frame().markUnchanged();
    }

    private static AuditEntry frame() {
        AuditEntry frame = CURRENT.get();
        if (frame == null) {
            throw new IllegalStateException("Audit is used outside an @Audited call. Is the method called through the "
                    + "Spring proxy (another bean, not this.method()) and is its class a Spring bean?");
        }
        return frame;
    }

    /** Opens the frame of one {@link Audited} call; the aspect calls {@link #close} in a finally block. */
    static AuditEntry open() {
        AuditEntry frame = new AuditEntry(CURRENT.get());
        CURRENT.set(frame);
        return frame;
    }

    static void close(AuditEntry frame) {
        if (frame.enclosing() == null) {
            CURRENT.remove(); // never leave a value on a thread that serves the next request
        } else {
            CURRENT.set(frame.enclosing());
        }
    }
}
