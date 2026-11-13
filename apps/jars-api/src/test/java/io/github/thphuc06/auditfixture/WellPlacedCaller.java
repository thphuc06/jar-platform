package io.github.thphuc06.auditfixture;

import org.springframework.stereotype.Service;

/** Calls the audited method of another bean, which goes through the proxy. */
@Service
public class WellPlacedCaller {

    private final WellPlaced other = new WellPlaced();

    public void run() {
        other.audited();
    }
}
