package io.github.thphuc06.jars.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/** A jar as other modules see it. {@code code} is a string on purpose: the JarCode enum stays internal to ledger. */
public record JarSummary(UUID id, String code, String name, BigDecimal balance, boolean archived) {

    public static final String POOL = "POOL";

    public boolean isPool() {
        return POOL.equals(code);
    }
}
