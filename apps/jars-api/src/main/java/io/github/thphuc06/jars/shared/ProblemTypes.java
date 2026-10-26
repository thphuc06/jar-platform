package io.github.thphuc06.jars.shared;

import java.net.URI;

/** Problem {@code type} URIs (RFC 9457). They are identifiers, not links: nothing is served at these addresses. */
public final class ProblemTypes {

    public static final String BASE = "https://jars.local/problems/";

    private ProblemTypes() {}

    public static URI of(String code) {
        return URI.create(BASE + code);
    }
}
