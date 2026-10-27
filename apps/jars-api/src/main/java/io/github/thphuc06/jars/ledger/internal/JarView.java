package io.github.thphuc06.jars.ledger.internal;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read-only copy of a jar handed to the web layer; entities never leave the ledger's service layer. */
public record JarView(
        UUID id, JarCode code, String name, @Nullable BigDecimal targetPct, BigDecimal balance, String currency) {

    static JarView of(Jar jar) {
        return new JarView(jar.id(), jar.code(), jar.name(), jar.targetPct(), jar.balance(), jar.currency());
    }
}
