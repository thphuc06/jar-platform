package io.github.thphuc06.jars.shared;

import java.util.Objects;

/**
 * A business rule was broken. {@code platform.web} maps it to a {@code ProblemDetail} using the
 * {@link ProblemType}; {@code detail} is shown to the client, so keep it free of internals.
 */
public class DomainException extends RuntimeException {

    private final ProblemType type;

    public DomainException(ProblemType type, String detail) {
        super(detail);
        this.type = Objects.requireNonNull(type, "type");
    }

    public ProblemType type() {
        return type;
    }

    /** 404 for a resource that does not exist <em>or does not belong to the caller</em> (anti-IDOR, see security.md). */
    public static DomainException notFound(String what) {
        return new DomainException(ProblemTypes.NOT_FOUND, what + " không tồn tại");
    }
}
