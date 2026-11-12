package io.github.thphuc06.jars.ledger.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A spending or income category. System categories have no owner and are visible to every user. */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    private UUID id;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private CategoryKind kind;

    protected Category() {} // for JPA

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public CategoryKind getKind() {
        return kind;
    }

    public boolean isSystem() {
        return userId == null;
    }
}
