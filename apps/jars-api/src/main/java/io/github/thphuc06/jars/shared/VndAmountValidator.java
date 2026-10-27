package io.github.thphuc06.jars.shared;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

/** Checks {@link VndAmount}. */
public final class VndAmountValidator implements ConstraintValidator<VndAmount, BigDecimal> {

    /** NUMERIC(19,2) leaves 17 digits before the decimal point. */
    static final int MAX_INTEGER_DIGITS = 17;

    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        // stripTrailingZeros: 85000.00 → 85000 (scale 0), 85000.50 → 85000.5 (scale 1), 100 → 1E+2 (scale -2)
        BigDecimal stripped = value.stripTrailingZeros();
        boolean whole = stripped.scale() <= 0;
        int integerDigits = stripped.precision() - stripped.scale();
        return value.signum() > 0 && whole && integerDigits <= MAX_INTEGER_DIGITS;
    }
}
