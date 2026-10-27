package io.github.thphuc06.jars.ledger.internal;

/** Kind of jar (BR-1). Stored as text; the same list is enforced by {@code ck_jars_code} in Flyway V3. */
public enum JarCode {
    /** Necessities. */
    NEC,
    /** Financial freedom: investments, never spent. */
    FFA,
    /** Long-term savings for big future expenses. */
    LTSS,
    /** Education. */
    EDU,
    /** Play: entertainment, shopping, travel. */
    PLAY,
    /** Giving: charity and gifts. */
    GIVE,
    /** Hidden system jar: income lands here until it is allocated. Has no target percentage, is never archived. */
    POOL,
    /** Created by the user; the only code that may appear more than once per user. */
    CUSTOM
}
