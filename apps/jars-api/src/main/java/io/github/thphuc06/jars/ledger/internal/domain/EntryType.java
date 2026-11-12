package io.github.thphuc06.jars.ledger.internal.domain;

/** Why a jar's balance moved. The amount of an entry is signed: positive into the jar, negative out of it. */
public enum EntryType {
    /** Income put into the jar. */
    ALLOCATE,
    /** An expense paid from the jar. */
    SPEND,
    TRANSFER_IN,
    TRANSFER_OUT,
    /** A correction, for example reversing an expense that was moved to another jar. */
    ADJUST
}
