package io.github.thphuc06.jars.identity.internal.web;

import io.github.thphuc06.jars.identity.CurrentUser;
import io.github.thphuc06.jars.identity.IdentityApi;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final IdentityApi identity;

    MeController(IdentityApi identity) {
        this.identity = identity;
    }

    /** Returns the signed-in user; the first call creates the internal user (flow F1). */
    @GetMapping
    MeResponse getMe() {
        return MeResponse.from(identity.currentUser());
    }

    record MeResponse(UUID id, String email, @Nullable String displayName, String locale, Instant createdAt) {
        static MeResponse from(CurrentUser user) {
            return new MeResponse(user.id(), user.email(), user.displayName(), user.locale(), user.createdAt());
        }
    }
}
