package io.github.thphuc06.jars.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public API of the ledger for other modules (synchronous reads). Other modules never see ledger entities or tables:
 * they get {@link JarSummary} copies and refer to jars by id only (docs/02-architecture/modules.md, rules 2–4).
 */
public interface LedgerApi {

    /** Every jar of the user, archived ones included (flagged), POOL included (code "POOL"). */
    List<JarSummary> jars(UUID userId);

    /** One jar, only if it belongs to the user. */
    Optional<JarSummary> jar(UUID userId, UUID jarId);
}
