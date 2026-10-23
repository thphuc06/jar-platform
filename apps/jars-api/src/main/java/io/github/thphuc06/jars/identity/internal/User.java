package io.github.thphuc06.jars.identity.internal;

import io.github.thphuc06.jars.identity.CurrentUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Row of table {@code users} (Flyway V1). Rows are inserted with SQL (see {@link UserRepository#insertIfAbsent}). */
@Entity
@Table(name = "users")
class User {

    @Id
    private UUID id;

    @Column(name = "cognito_sub", nullable = false, updatable = false)
    private String cognitoSub;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(nullable = false)
    private String locale;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;

    protected User() {} // for JPA

    CurrentUser toCurrentUser() {
        return new CurrentUser(id, email, displayName, locale, createdAt);
    }

    UUID id() {
        return id;
    }
}
