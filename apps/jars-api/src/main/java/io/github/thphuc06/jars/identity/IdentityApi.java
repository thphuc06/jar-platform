package io.github.thphuc06.jars.identity;

/** Public API of the identity module. */
public interface IdentityApi {

    /**
     * Returns the user behind the current request's access token, creating the internal user on first use.
     *
     * @throws IllegalStateException when called outside an authenticated request
     */
    CurrentUser currentUser();
}
