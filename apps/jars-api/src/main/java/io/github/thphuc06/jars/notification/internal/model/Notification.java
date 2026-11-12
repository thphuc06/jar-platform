package io.github.thphuc06.jars.notification.internal.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Something the user should know about. Ids are UUIDv7, so "newer than id X" is just {@code id > X}, which is how
 * missed notifications are replayed after an SSE reconnect.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @Column(name = "severity", nullable = false, updatable = false)
    private String severity;

    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Column(name = "ref_type", updatable = false)
    private String refType;

    @Column(name = "ref_id", updatable = false)
    private UUID refId;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {} // for JPA

    public Notification(
            UUID userId,
            String type,
            String severity,
            String title,
            String body,
            String refType,
            UUID refId,
            Instant createdAt) {
        this.userId = userId;
        this.type = type;
        this.severity = severity;
        this.title = title;
        this.body = body;
        this.refType = refType;
        this.refId = refId;
        this.createdAt = createdAt;
    }

    /** Marks it read; reading twice keeps the first time. */
    public void markRead(Instant now) {
        if (readAt == null) {
            this.readAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getType() {
        return type;
    }

    public String getSeverity() {
        return severity;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getRefType() {
        return refType;
    }

    public UUID getRefId() {
        return refId;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
