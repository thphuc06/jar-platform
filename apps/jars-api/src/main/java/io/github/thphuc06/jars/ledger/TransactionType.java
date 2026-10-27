package io.github.thphuc06.jars.ledger;

/** Direction of a money movement (business-rules BR-2). The amount itself is always positive. */
public enum TransactionType {
    /** Money in: lands in POOL unless the user picked a jar. */
    INCOME,
    /** Money out of one jar (never POOL). */
    EXPENSE,
    /** Moves money between two of the user's jars; allocating income is a transfer out of POOL. */
    JAR_TRANSFER
}
