package io.github.thphuc06.jars.budgeting.internal.domain;

import io.github.thphuc06.jars.shared.ProblemType;

/** Problem types raised by budgeting rules. */
public final class BudgetProblems {

    public static final ProblemType INVALID_ALLOCATION =
            new ProblemType("invalid-allocation", 422, "Phân bổ thu nhập không hợp lệ");

    private BudgetProblems() {}
}
