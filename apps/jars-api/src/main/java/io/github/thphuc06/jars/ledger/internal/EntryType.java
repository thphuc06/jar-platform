package io.github.thphuc06.jars.ledger.internal;

/** Kind of ledger entry; the sign of the amount follows it ({@code ck_jar_entries_sign} in Flyway V4). */
enum EntryType {
    INCOME(Direction.IN),
    SPEND(Direction.OUT),
    TRANSFER_IN(Direction.IN),
    TRANSFER_OUT(Direction.OUT),
    /** Correction, e.g. the reversing pair written when a transaction is moved to another jar (PATCH). */
    ADJUST(Direction.EITHER);

    private enum Direction {
        IN,
        OUT,
        EITHER
    }

    private final Direction direction;

    EntryType(Direction direction) {
        this.direction = direction;
    }

    boolean allows(boolean moneyIn) {
        return direction == Direction.EITHER || (direction == Direction.IN) == moneyIn;
    }
}
