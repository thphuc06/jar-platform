package io.github.thphuc06.jars.identity;

import io.github.thphuc06.jars.shared.UserId;
import java.util.List;
import java.util.UUID;

/** The identity module as seen by other modules. */
public interface IdentityApi {

    /** Largest page {@link #userIdsAfter} will return. */
    int MAX_PAGE = 1000;

    /**
     * One page of user ids in ascending id order, for batch jobs that visit every user (such as the month close).
     * Keyset pagination: pass the last id of the previous page to get the next one, or {@code null} for the first page.
     * An empty list means there are no more users. Ids are UUIDv7, so users created while a job runs are visited too.
     *
     * @param cursor exclusive lower bound, or null to start from the beginning
     * @param limit page size, 1 to {@link #MAX_PAGE}
     */
    List<UserId> userIdsAfter(UUID cursor, int limit);
}
