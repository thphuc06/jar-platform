package io.github.thphuc06.jars.imports.internal.parsing;

/** The file as a whole cannot be imported (no usable header, too large, already imported). The message is for the user. */
public class InvalidStatementException extends RuntimeException {

    public InvalidStatementException(String message) {
        super(message);
    }
}
