package io.github.thphuc06.jars.ledger;

import io.github.thphuc06.jars.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

/** What other modules may know about a jar. */
public record JarSummary(UUID id, String code, String name, BigDecimal targetPct, Money balance) {}
