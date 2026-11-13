package io.github.thphuc06.jars.identity.internal.domain;

import io.github.thphuc06.jars.identity.AgeBand;
import io.github.thphuc06.jars.identity.IncomeSituation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One person, identified by the Cognito {@code sub}. Created lazily on the first authenticated request (see
 * {@code UserService}); the profile fields are the only thing the user can change.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "cognito_sub", nullable = false, updatable = false)
    private String cognitoSub;

    @Column(name = "email")
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "locale", nullable = false)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_band")
    private AgeBand ageBand;

    @Enumerated(EnumType.STRING)
    @Column(name = "income_situation")
    private IncomeSituation incomeSituation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {} // for JPA

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getLocale() {
        return locale;
    }

    public AgeBand getAgeBand() {
        return ageBand;
    }

    public IncomeSituation getIncomeSituation() {
        return incomeSituation;
    }

    /** Only non-null arguments are applied; a blank display name clears it. */
    public void updateProfile(
            String newDisplayName,
            String newLocale,
            AgeBand newAgeBand,
            IncomeSituation newIncomeSituation,
            Instant now) {
        if (newDisplayName != null) {
            String trimmed = newDisplayName.trim();
            this.displayName = trimmed.isEmpty() ? null : trimmed;
        }
        if (newLocale != null) {
            this.locale = newLocale;
        }
        if (newAgeBand != null) {
            this.ageBand = newAgeBand;
        }
        if (newIncomeSituation != null) {
            this.incomeSituation = newIncomeSituation;
        }
        this.updatedAt = now;
    }
}
