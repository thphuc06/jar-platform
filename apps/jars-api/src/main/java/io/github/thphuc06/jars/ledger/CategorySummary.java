package io.github.thphuc06.jars.ledger;

import java.util.UUID;

/** A category as other modules need it, mostly to show a name next to an id. */
public record CategorySummary(UUID id, String name, String kind) {}
