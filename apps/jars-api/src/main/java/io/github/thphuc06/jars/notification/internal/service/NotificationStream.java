package io.github.thphuc06.jars.notification.internal.service;

import io.github.thphuc06.jars.shared.UserId;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Opens an SSE stream: reads what the client missed from the database, then attaches it to the live registry. */
@Service
public class NotificationStream {

    private final NotificationService notifications;
    private final SseRegistry registry;

    NotificationStream(NotificationService notifications, SseRegistry registry) {
        this.notifications = notifications;
        this.registry = registry;
    }

    public SseEmitter open(UserId userId, String lastEventId) {
        return registry.subscribe(userId, notifications.missedSince(userId, lastEventId));
    }
}
