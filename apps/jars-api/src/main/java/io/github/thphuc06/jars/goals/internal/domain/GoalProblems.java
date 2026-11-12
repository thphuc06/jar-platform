package io.github.thphuc06.jars.goals.internal.domain;

import io.github.thphuc06.jars.shared.ProblemType;

/** Problem types raised by goal rules. */
public final class GoalProblems {

    public static final ProblemType INVALID_GOAL = new ProblemType("invalid-goal", 422, "Mục tiêu không hợp lệ");

    private GoalProblems() {}
}
