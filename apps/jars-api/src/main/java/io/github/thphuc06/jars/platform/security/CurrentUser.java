package io.github.thphuc06.jars.platform.security;

import io.github.thphuc06.jars.platform.observability.MdcKeys;
import io.github.thphuc06.jars.shared.UserId;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The caller of the current request as an internal {@link UserId}: the validated token's {@code sub} mapped through
 * {@link UserDirectory} (which creates the user on first sight), looked up once per request. The one place that does
 * this, shared by the controller argument resolver and the audit aspect so both always agree on who the caller is.
 *
 * <p>Resolving the user also puts the id into the logging MDC ({@link MdcKeys#USER_ID}); the trace filter removes it
 * when the request ends.
 */
public class CurrentUser {

    private static final String CACHE_ATTRIBUTE = CurrentUser.class.getName() + ".userId";

    private final ObjectProvider<UserDirectory> directory;

    CurrentUser(ObjectProvider<UserDirectory> directory) {
        this.directory = directory;
    }

    /** The authenticated caller, or empty when nothing authenticated this thread (a scheduled job, a test). */
    public Optional<UserId> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            return Optional.empty();
        }
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        UserId userId = request == null
                ? null
                : (UserId) request.getAttribute(CACHE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (userId == null) {
            userId = directory.getObject().resolve(jwt.getToken().getSubject());
            if (request != null) {
                request.setAttribute(CACHE_ATTRIBUTE, userId, RequestAttributes.SCOPE_REQUEST);
            }
        }
        MDC.put(MdcKeys.USER_ID, userId.toString());
        return Optional.of(userId);
    }

    /** @throws AuthenticationCredentialsNotFoundException when the request is not authenticated */
    public UserId require() {
        return find().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No authenticated user"));
    }
}
