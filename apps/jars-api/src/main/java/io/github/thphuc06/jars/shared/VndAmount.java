package io.github.thphuc06.jars.shared;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * BR-5: a positive amount of whole Vietnamese dong that fits {@code NUMERIC(19,2)}. {@code "85000"} and
 * {@code "85000.00"} are valid, {@code "85000.50"} is not. {@code null} counts as valid; combine with
 * {@code @NotNull}, as for every Bean Validation constraint.
 */
@Documented
@Constraint(validatedBy = VndAmountValidator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface VndAmount {

    String message() default "must be a positive whole number of VND (at most 17 digits, no decimals)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
