package io.github.thphuc06.jars.shared;

import java.util.Objects;
import java.util.UUID;

/** Internal id of a user ({@code users.id}), as opposed to the Cognito {@code sub}. */
public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "value");
    }

    public static UserId of(String uuid) {
        return new UserId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
