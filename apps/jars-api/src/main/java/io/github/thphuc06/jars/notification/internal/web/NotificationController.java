package io.github.thphuc06.jars.notification.internal.web;

import io.github.thphuc06.jars.notification.internal.service.NotificationService;
import io.github.thphuc06.jars.notification.internal.service.NotificationService.NotificationPage;
import io.github.thphuc06.jars.notification.internal.service.NotificationStream;
import io.github.thphuc06.jars.notification.internal.service.NotificationView;
import io.github.thphuc06.jars.shared.UserId;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService notifications;
    private final NotificationStream stream;

    NotificationController(NotificationService notifications, NotificationStream stream) {
        this.notifications = notifications;
        this.stream = stream;
    }

    @GetMapping
    NotificationPage list(
            UserId userId,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return notifications.list(userId, unreadOnly, limit, cursor);
    }

    @PostMapping("/{id}/read")
    NotificationView read(UserId userId, @PathVariable UUID id) {
        return notifications.markRead(userId, id);
    }

    /**
     * Server-Sent Events. Browsers' {@code EventSource} cannot send an Authorization header, so clients use
     * {@code fetch} and, after a dropped connection, send the id of the last event they saw as {@code Last-Event-ID}.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(UserId userId, @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return stream.open(userId, lastEventId);
    }
}
