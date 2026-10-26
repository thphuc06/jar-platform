package io.github.thphuc06.jars.shared;

/**
 * The resource does not exist OR does not belong to the caller. Both cases look the same to the client on purpose,
 * so nobody can probe which ids exist (IDOR protection).
 */
public final class NotFoundException extends DomainException {

    public NotFoundException(String resource, Object id) {
        super(resource + "-not-found", resource + " " + id + " was not found");
    }
}
