package io.github.thphuc06.jars.ledger.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Row of table {@code categories} (Flyway V3). {@code userId == null} marks a system default (BR-3), seeded by the
 * migration and shared by every user. Read-only for now; user-defined categories come with the categories API.
 */
@Entity
@Table(name = "categories")
class Category {

    @Id
    private UUID id;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private CategoryKind kind;

    /** Jar the UI suggests when this category is picked; a code, because each user has their own jar ids. */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_jar_code")
    private JarCode defaultJarCode;

    protected Category() {} // for JPA

    UUID id() {
        return id;
    }

    @Nullable
    UUID userId() {
        return userId;
    }

    String name() {
        return name;
    }

    CategoryKind kind() {
        return kind;
    }

    @Nullable
    JarCode defaultJarCode() {
        return defaultJarCode;
    }
}
