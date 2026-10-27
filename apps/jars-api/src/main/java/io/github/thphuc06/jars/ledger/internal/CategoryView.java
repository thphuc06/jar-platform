package io.github.thphuc06.jars.ledger.internal;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read-only copy of a category; {@code system} marks the shared BR-3 defaults. */
public record CategoryView(
        UUID id, String name, CategoryKind kind, @Nullable JarCode defaultJarCode, boolean system) {

    static CategoryView of(Category c) {
        return new CategoryView(c.id(), c.name(), c.kind(), c.defaultJarCode(), c.userId() == null);
    }
}
