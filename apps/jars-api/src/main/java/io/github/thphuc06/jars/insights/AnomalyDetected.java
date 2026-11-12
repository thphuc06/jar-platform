package io.github.thphuc06.jars.insights;

import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Instant;
import java.util.UUID;

/**
 * Spending in a jar looked unusual. The {@code notification} module turns it into a notification. Names are included
 * so the listener does not have to ask the ledger again.
 *
 * @param eventId unique per publication (UUIDv7); consumers use it to ignore a redelivered event
 * @param detector {@code MAD} (a single large expense) or {@code CUSUM} (a sustained shift)
 * @param direction {@code UP} (more than usual) or {@code DOWN}
 * @param severity {@code LOW}, {@code MEDIUM} or {@code HIGH}
 * @param score how far the expense was from normal, in the detector's own unit
 */
public record AnomalyDetected(
        UUID eventId,
        UUID anomalyId,
        UserId userId,
        UUID jarId,
        String jarName,
        String detector,
        String direction,
        String severity,
        double score,
        double threshold,
        UUID transactionId,
        Money amount,
        Instant detectedAt) {}
