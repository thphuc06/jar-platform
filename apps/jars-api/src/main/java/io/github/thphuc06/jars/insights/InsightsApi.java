package io.github.thphuc06.jars.insights;

import io.github.thphuc06.jars.shared.UserId;
import java.util.List;

/** The insights module as seen by other modules: read-only views over its read models. */
public interface InsightsApi {

    /** Longest history {@link #monthlyCashflow} will return. */
    int MAX_MONTHS = 60;

    /** The calendar months before the current one that {@link #spendingBehaviour} averages over. */
    int BASELINE_MONTHS = 3;

    /** Fewer baseline months than this and {@link #spendingBehaviour} offers no "usual" level. */
    int MIN_BASELINE_MONTHS = 2;

    /** Most categories {@link #spendingBehaviour} lists. */
    int MAX_CATEGORIES = 8;

    /**
     * Income and spending per month for the last {@code lastMonths} calendar months ending with the current month
     * (Vietnam time), oldest first. Months without any recorded income or spending are left out, so an empty list
     * means the user has no history at all. The current month is partial: callers that estimate a monthly pace usually
     * prefer the complete months before it.
     *
     * @param lastMonths how many calendar months to look at, 1 to {@link #MAX_MONTHS}
     */
    List<MonthlyCashflow> monthlyCashflow(UserId userId, int lastMonths);

    /** How many anomalies the user has not yet given feedback on. */
    long openAnomalyCount(UserId userId);

    /**
     * The user's anomalies still awaiting feedback, most recently detected first.
     *
     * @param limit how many to return, at least 1; the total is {@link #openAnomalyCount}
     */
    List<OpenAnomaly> openAnomalies(UserId userId, int limit);

    /**
     * Spending per category in the current month (Vietnam time) against the average of the {@link #BASELINE_MONTHS}
     * complete months before it. Built on the monthly category read model, so it follows the user's categorisation.
     */
    SpendingBehaviour spendingBehaviour(UserId userId);
}
