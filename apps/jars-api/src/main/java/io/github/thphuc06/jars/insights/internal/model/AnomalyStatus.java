package io.github.thphuc06.jars.insights.internal.model;

/** Lifecycle of an alert. The user's feedback moves it out of {@code OPEN}. */
public enum AnomalyStatus {
    OPEN,
    /** "Yes, that was unusual": a true positive. */
    CONFIRMED,
    /** "I expected that": real but not a problem (a planned purchase). */
    EXPECTED,
    /** "That is not unusual": a false positive. */
    FALSE_POSITIVE;

    public boolean isFeedback() {
        return this != OPEN;
    }
}
