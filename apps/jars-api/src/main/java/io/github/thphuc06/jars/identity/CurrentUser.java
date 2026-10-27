package io.github.thphuc06.jars.identity;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The signed-in user as seen by every module. {@code id} is the internal id; never use the Cognito sub elsewhere. */
public record CurrentUser(UUID id, String email, @Nullable String displayName, String locale, Instant createdAt) {}
