/**
 * Who the caller is: maps a Cognito identity (JWT {@code sub}) to an internal user, created lazily on the first
 * authenticated request (flow F1). Other modules ask {@link io.github.thphuc06.jars.identity.IdentityApi} for the
 * current user's id and never read JWT claims themselves.
 */
@ApplicationModule(displayName = "Identity")
package io.github.thphuc06.jars.identity;

import org.springframework.modulith.ApplicationModule;
