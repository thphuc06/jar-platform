package io.github.thphuc06.jars.platform.security;

import io.github.thphuc06.jars.shared.UserId;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets controllers declare a {@link UserId} parameter. The id always comes from the validated token, never from the
 * path or body, which is what keeps every query scoped to the caller (security.md §2).
 */
class UserIdArgumentResolver implements HandlerMethodArgumentResolver {

    private final CurrentUser currentUser;

    UserIdArgumentResolver(CurrentUser currentUser) {
        this.currentUser = currentUser;
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
        return currentUser.require();
    }
}
