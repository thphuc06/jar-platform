package io.github.thphuc06.jars.ledger;

import io.github.thphuc06.jars.shared.UserId;
import java.util.List;

/** The ledger as seen by other modules. Money movements go through the ledger only; nobody else writes its tables. */
public interface LedgerApi {

    /** The user's active (not archived) jars, oldest first. */
    List<JarSummary> jars(UserId userId);

    /** System categories plus the user's own. */
    List<CategorySummary> categories(UserId userId);
}
