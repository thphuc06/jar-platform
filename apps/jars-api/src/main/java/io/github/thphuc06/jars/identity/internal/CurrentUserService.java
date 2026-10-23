package io.github.thphuc06.jars.identity.internal;

import io.github.thphuc06.jars.identity.CurrentUser;
import io.github.thphuc06.jars.identity.IdentityApi;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/** Resolves the caller from the security context. The user id ALWAYS comes from the verified token, never from input. */
@Service
class CurrentUserService implements IdentityApi {

    private final UserRepository users;
    private final UserRegistrar registrar;
    private final UserInfoClient userInfo;

    CurrentUserService(UserRepository users, UserRegistrar registrar, UserInfoClient userInfo) {
        this.users = users;
        this.registrar = registrar;
        this.userInfo = userInfo;
    }

    @Override
    public CurrentUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException("no authenticated JWT in the security context");
        }
        String sub = jwtAuth.getToken().getSubject();
        return users.findByCognitoSub(sub)
                .orElseGet(() -> {
                    // first request of this user: read the e-mail OUTSIDE any DB transaction (network call),
                    // then insert in a short transaction
                    String email =
                            userInfo.fetch(jwtAuth.getToken().getTokenValue()).email();
                    return registrar.register(sub, email);
                })
                .toCurrentUser();
    }
}
