package io.github.thphuc06.jars.identity.internal.service;

import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.identity.internal.persistence.UserRepository;
import io.github.thphuc06.jars.shared.UserId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityApiImpl implements IdentityApi {

    /** Sorts before every real id (user ids are UUIDv7, whose first bits are a recent timestamp). */
    private static final UUID BEGINNING = new UUID(0L, 0L);

    private final UserRepository users;

    IdentityApiImpl(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserId> userIdsAfter(UUID cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE);
        }
        return users.findIdsAfter(cursor == null ? BEGINNING : cursor, Limit.of(limit)).stream()
                .map(UserId::new)
                .toList();
    }
}
