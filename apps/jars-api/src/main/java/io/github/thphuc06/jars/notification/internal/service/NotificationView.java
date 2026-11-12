package io.github.thphuc06.jars.notification.internal.service;

import io.github.thphuc06.jars.notification.internal.model.Notification;
import java.time.Instant;
import java.util.UUID;

/** A notification as the API and the SSE stream show it. */
public record NotificationView(
        UUID id,
        String type,
        String severity,
        String title,
        String body,
        String refType,
        UUID refId,
        Instant readAt,
        Instant createdAt) {

    static NotificationView of(Notification n) {
        return new NotificationView(
                n.getId(),
                n.getType(),
                n.getSeverity(),
                n.getTitle(),
                n.getBody(),
                n.getRefType(),
                n.getRefId(),
                n.getReadAt(),
                n.getCreatedAt());
    }
}
