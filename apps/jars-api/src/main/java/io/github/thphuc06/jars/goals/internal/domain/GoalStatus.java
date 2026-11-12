package io.github.thphuc06.jars.goals.internal.domain;

public enum GoalStatus {
    /** Being saved for. */
    ACTIVE,
    /** The user marked the target as reached. */
    ACHIEVED,
    /** Removed by the user. An archived goal is invisible: every operation on it answers 404. */
    ARCHIVED
}
