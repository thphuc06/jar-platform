package io.github.thphuc06.jars.platform.security;

import io.github.thphuc06.jars.shared.UserId;

/**
 * Maps the Cognito {@code sub} of the caller to the internal {@link UserId}, creating the user on first sight
 * (flow F1). Declared here and implemented by the {@code identity} module so {@code platform} never depends on a
 * business module.
 */
public interface UserDirectory {

    UserId resolve(String subject);
}
