package io.github.thphuc06.jars.ledger.internal;

/** Kind of ledger entry; the sign of the amount follows it ({@code ck_jar_entries_sign} in Flyway V4). */
enum EntryType {
    INCOME(true),
    SPEND(false),
    TRANSFER_IN(true),
    TRANSFER_OUT(false);
    // ADJUST (either sign) arrives with corrections; the column already allows it

    private final boolean moneyIn;

    EntryType(boolean moneyIn) {
        this.moneyIn = moneyIn;
    }

    boolean isMoneyIn() {
        return moneyIn;
    }
}
