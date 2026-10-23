package io.github.thphuc06.modulithfixture.beta;

import io.github.thphuc06.modulithfixture.alpha.internal.AlphaSecret;

/** Test fixture: deliberately violates the module boundary by using alpha's internal class. */
public class BetaService {

    public String leak() {
        return new AlphaSecret().value();
    }
}
