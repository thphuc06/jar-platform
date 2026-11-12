package io.github.thphuc06.jars.identity.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    /** Only non-null arguments are applied; a blank display name clears it. */
    public void updateProfile(String newDisplayName, String newLocale, Instant now) {
        if (newDisplayName != null) {
            String trimmed = newDisplayName.trim();
            this.displayName = trimmed.isEmpty() ? null : trimmed;
        }
        if (newLocale != null) {
            this.locale = newLocale;
        }
        this.updatedAt = now;
    }
}
