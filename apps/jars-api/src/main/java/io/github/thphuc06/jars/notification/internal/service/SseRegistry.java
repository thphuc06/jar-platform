package io.github.thphuc06.jars.notification.internal.service;

import io.github.thphuc06.jars.shared.UserId;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The open Server-Sent-Events connections of this instance (ADR-0015). A user can have several (tabs, devices).
 *
 * <p>With one task that is all that is needed. With several tasks a notification created on task A would not reach a
 * connection held by task B; the planned fix is a Valkey pub/sub channel per user (flows.md F3), not done yet.
 *
 * <p>A heartbeat comment goes out every 15 s so CloudFront and the ALB (30 s idle timeout) never see a silent
 * connection, and dead connections are detected and dropped when a write fails.
 */
@Component
public class SseRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseRegistry.class);

    static final long HEARTBEAT_SECONDS = 15;
    static final long EMITTER_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);

    private final Map<UUID, Set<SseEmitter>> byUser = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    SseRegistry() {
        heartbeat.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Opens a stream for the user. The emitter is registered first and the missed notifications are sent afterwards, so
     * nothing created in between is lost; a client may therefore see one notification twice and should de-duplicate by id.
     */
    SseEmitter subscribe(UserId user, List<NotificationView> missed) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        Set<SseEmitter> emitters = byUser.computeIfAbsent(user.value(), k -> ConcurrentHashMap.newKeySet());
        emitters.add(emitter);
        Runnable cleanup = () -> remove(user.value(), emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            send(emitter, SseEmitter.event().comment("connected"));
            for (NotificationView view : missed) {
                send(emitter, event(view));
            }
        } catch (IOException | IllegalStateException e) {
            cleanup.run();
        }
        return emitter;
    }

    /** Pushes a notification to every open connection of the user. Call after the creating transaction committed. */
    public void publish(UserId user, NotificationView view) {
        Set<SseEmitter> emitters = byUser.get(user.value());
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                send(emitter, event(view));
            } catch (IOException | IllegalStateException e) {
                remove(user.value(), emitter);
            }
        }
    }

    int connections(UserId user) {
        Set<SseEmitter> emitters = byUser.get(user.value());
        return emitters == null ? 0 : emitters.size();
    }

    void sendHeartbeats() {
        byUser.forEach((userId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    send(emitter, SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException e) {
                    remove(userId, emitter);
                }
            }
        });
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        byUser.values().forEach(set -> set.forEach(SseEmitter::complete));
        byUser.clear();
    }

    private static SseEmitter.SseEventBuilder event(NotificationView view) {
        return SseEmitter.event().id(view.id().toString()).name("notification").data(view, MediaType.APPLICATION_JSON);
    }

    /** One writer at a time per connection: SseEmitter is not thread-safe, and publish/heartbeat run on other threads. */
    private static void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) throws IOException {
        synchronized (emitter) {
            emitter.send(event);
        }
    }

    private void remove(UUID userId, SseEmitter emitter) {
        Set<SseEmitter> emitters = byUser.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                byUser.remove(userId, emitters);
            }
        }
        log.debug("SSE connection of {} closed", userId);
    }
}
