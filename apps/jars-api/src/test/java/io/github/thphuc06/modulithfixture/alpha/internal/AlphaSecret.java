package io.github.thphuc06.modulithfixture.alpha.internal;

/** Test fixture: internal to module "alpha"; other modules must not use it. */
public class AlphaSecret {

    public String value() {
        return "secret";
    }
}
