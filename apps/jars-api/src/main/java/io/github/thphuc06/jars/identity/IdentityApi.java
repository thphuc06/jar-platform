package io.github.thphuc06.jars.identity;

import java.util.Optional;
import java.util.UUID;

/** Public API of the identity module. */
public interface IdentityApi {

    /**
     * Returns the user behind the current request's access token, creating the internal user on first use.
     *
     * @throws IllegalStateException when called outside an authenticated request
     */
    CurrentUser currentUser();

    /**
     * The internal id of the user with this e-mail (case-insensitive), if that user has signed in at least once. For
     * tooling outside a request, e.g. the local demo-data seeder; request handling always uses {@link #currentUser()}.
     */
    Optional<UUID> findUserIdByEmail(String email);
}
