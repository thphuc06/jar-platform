package io.github.thphuc06.jars.insights;

import io.github.thphuc06.jars.shared.UserId;
import java.util.List;

/** The insights module as seen by other modules: read-only views over its read models. */
public interface InsightsApi {

    /** Longest history {@link #monthlyCashflow} will return. */
    int MAX_MONTHS = 60;

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
}
