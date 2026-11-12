package io.github.thphuc06.jars.ledger.internal.domain;

import io.github.thphuc06.jars.shared.ProblemType;

/** Problem types raised by ledger rules. */
public final class LedgerProblems {

    public static final ProblemType INSUFFICIENT_JAR_BALANCE =
            new ProblemType("insufficient-jar-balance", 422, "Số dư hũ không đủ");
    public static final ProblemType JAR_ARCHIVED = new ProblemType("jar-archived", 422, "Hũ đã được lưu trữ");
    public static final ProblemType JAR_NOT_EMPTY =
            new ProblemType("jar-not-empty", 422, "Hũ vẫn còn số dư nên chưa thể lưu trữ");
    public static final ProblemType ALLOCATION_EXCEEDS_100 =
            new ProblemType("allocation-exceeds-100", 422, "Tổng tỷ lệ các hũ vượt quá 100%");
    public static final ProblemType INVALID_TRANSACTION =
            new ProblemType("invalid-transaction", 422, "Giao dịch không hợp lệ");

    private LedgerProblems() {}
}
