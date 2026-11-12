package io.github.thphuc06.jars.identity.internal.service;

import io.github.thphuc06.jars.identity.UserRegistered;
import io.github.thphuc06.jars.identity.internal.domain.User;
import io.github.thphuc06.jars.identity.internal.persistence.RiskAssessmentRepository;
import io.github.thphuc06.jars.identity.internal.persistence.UserRepository;
import io.github.thphuc06.jars.platform.security.UserDirectory;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Maps the caller's Cognito {@code sub} to an internal user, creating it on first sight (flow F1), and edits the
 * profile. Implements {@link UserDirectory} so {@code platform} can resolve the current user without depending on
 * this module.
 */
@Service
public class UserService implements UserDirectory {

    static final String DEFAULT_LOCALE = "vi-VN";

    /** The profile as the API shows it. */
    public record Profile(UUID id, String email, String displayName, String locale, boolean onboardingCompleted) {}

    private final UserRepository users;
    private final RiskAssessmentRepository assessments;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    UserService(
            UserRepository users,
            RiskAssessmentRepository assessments,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            Clock clock) {
        this.users = users;
        this.assessments = assessments;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public UserId resolve(String subject) {
        return users.findIdByCognitoSub(subject).map(UserId::new).orElseGet(() -> provision(subject));
    }

    @Transactional(readOnly = true)
    public Profile profile(UserId userId) {
        User user = users.findById(userId.value()).orElseThrow(() -> DomainException.notFound("Người dùng"));
        return toProfile(user);
    }

    @Transactional
    public Profile updateProfile(UserId userId, String displayName, String locale) {
        User user = users.findById(userId.value()).orElseThrow(() -> DomainException.notFound("Người dùng"));
        user.updateProfile(displayName, locale, clock.instant());
        return toProfile(user);
    }

    private UserId provision(String subject) {
        UUID id = Uuids.v7();
        // One transaction for the insert and the synchronous listeners (the ledger creates the default jars), so a
        // user never exists without them.
        Boolean created = transactions.execute(status -> {
            int inserted = users.insertIfAbsent(id, subject, DEFAULT_LOCALE, clock.instant());
            if (inserted == 1) {
                events.publishEvent(new UserRegistered(new UserId(id), DEFAULT_LOCALE));
            }
            return inserted == 1;
        });
        // lost the race: the winner has committed (or is committing), read its id
        UUID resolved = Boolean.TRUE.equals(created)
                ? id
                : users.findIdByCognitoSub(subject).orElseThrow(() -> new IllegalStateException("User vanished"));
        return new UserId(resolved);
    }

    private Profile toProfile(User user) {
        return new Profile(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getLocale(),
                assessments.existsByUserId(user.getId()));
    }
}
