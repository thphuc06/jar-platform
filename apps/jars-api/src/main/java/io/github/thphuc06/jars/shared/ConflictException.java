package io.github.thphuc06.jars.shared;

/** The request conflicts with the current state, e.g. {@code email-already-registered}. Retrying will not help. */
public final class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
