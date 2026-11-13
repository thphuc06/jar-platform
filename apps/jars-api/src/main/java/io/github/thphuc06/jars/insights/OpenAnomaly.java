package io.github.thphuc06.jars.insights;

import io.github.thphuc06.jars.shared.Money;
import java.time.LocalDate;

/**
 * An anomaly the user has not yet given feedback on, as other modules see it.
 *
 * @param jarName the jar's current name, or {@code null} when the jar has been archived
 * @param kind what the detector found, see {@link Kind}
 * @param severity {@code LOW}, {@code MEDIUM} or {@code HIGH}
 * @param amount the expense that raised the alert (for a drift, the latest one of the series)
 * @param usualAmount the jar's running mean expense (non-spike expenses only), rounded to whole VND. It is the value
 *     <em>now</em>, not at detection time, so it may have moved a little since; {@code null} when the jar has no
 *     baseline
 * @param detectedOn the day (Vietnam time) the alert was raised, which is when the expense was recorded; the
 *     anomaly does not store the transaction's own date
 */
public record OpenAnomaly(
        String jarName, Kind kind, String severity, Money amount, Money usualAmount, LocalDate detectedOn) {

    public enum Kind {
        /** One expense far above the jar's recent ones (MAD detector). */
        SPIKE,
        /** Expenses have been higher than usual for a while (CUSUM, upward). */
        DRIFT_UP,
        /** Expenses have been lower than usual for a while (CUSUM, downward). */
        DRIFT_DOWN
    }
}
