package io.github.thphuc06.jars.ledger.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code jars.ledger}.
 *
 * @param blockOverspend when true an expense that exceeds the jar's balance is rejected; when false (default) it is
 *     recorded because the money has already been spent and the negative balance shows the overspend. Transfers
 *     between jars always need enough money in the source jar.
 * @param maxAttempts how many times a posting is tried when it keeps losing optimistic-lock races
 */
@ConfigurationProperties("jars.ledger")
public record LedgerProperties(boolean blockOverspend, int maxAttempts) {

    public LedgerProperties {
        if (maxAttempts <= 0) {
            maxAttempts = 5;
        }
    }
}
