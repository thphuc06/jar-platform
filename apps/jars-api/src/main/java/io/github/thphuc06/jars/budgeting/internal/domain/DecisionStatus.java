package io.github.thphuc06.jars.budgeting.internal.domain;

/** What the user did with a suggested split. */
public enum DecisionStatus {
    /** Applied exactly as suggested. */
    ACCEPTED,
    /** Applied after changing it. */
    EDITED,
    /** Declined; the income gets no split (it can still be recorded on its own through the ledger). */
    SKIPPED
}
