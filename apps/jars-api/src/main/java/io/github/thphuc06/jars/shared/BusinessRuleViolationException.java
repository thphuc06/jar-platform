package io.github.thphuc06.jars.shared;

/** The request is well-formed but breaks a business rule, e.g. {@code insufficient-jar-balance} (BR-2). */
public final class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(String code, String message) {
        super(code, message);
    }
}
