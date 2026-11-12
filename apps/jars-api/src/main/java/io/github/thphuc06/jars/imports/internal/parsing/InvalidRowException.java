package io.github.thphuc06.jars.imports.internal.parsing;

/** One row cannot be imported; the batch step skips it and records the message (in Vietnamese, for the user). */
public class InvalidRowException extends RuntimeException {

    public InvalidRowException(String message) {
        super(message);
    }
}
