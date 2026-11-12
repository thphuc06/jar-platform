package io.github.thphuc06.jars.shared;

/**
 * A kind of problem the API reports as RFC 9457 {@code application/problem+json}.
 * {@code status} is a plain int so this class stays free of web dependencies.
 */
public record ProblemType(String slug, int status, String title) {

    private static final String BASE = "https://jars.local/problems/";

    public String uri() {
        return BASE + slug;
    }
}
