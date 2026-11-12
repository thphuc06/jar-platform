package io.github.thphuc06.jars.platform.security;

import io.github.thphuc06.jars.shared.UserId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets controllers declare a {@link UserId} parameter. The id always comes from the validated token, never from the
 * path or body, which is what keeps every query scoped to the caller (security.md §2).
 */
class UserIdArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String CACHE_ATTRIBUTE = UserIdArgumentResolver.class.getName() + ".userId";

    private final ObjectProvider<UserDirectory> directory;

    UserIdArgumentResolver(ObjectProvider<UserDirectory> directory) {
        this.directory = directory;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return UserId.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        UserId cached = (UserId) webRequest.getAttribute(CACHE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (cached != null) {
            return cached;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated user");
        }
        UserId userId = directory.getObject().resolve(jwt.getToken().getSubject());
        webRequest.setAttribute(CACHE_ATTRIBUTE, userId, RequestAttributes.SCOPE_REQUEST);
        return userId;
    }
}
