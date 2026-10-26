package io.github.thphuc06.jars.shared;

/**
 * Base of all business errors thrown by modules. It knows nothing about HTTP: the {@code code} becomes the problem
 * {@code type} ({@code https://jars.local/problems/{code}}) and the platform decides the status from the subclass.
 * The UI translates messages by {@code code}, so codes are stable identifiers (kebab-case) and part of the API.
 */
public abstract sealed class DomainException extends RuntimeException
        permits NotFoundException, BusinessRuleViolationException, ConflictException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
