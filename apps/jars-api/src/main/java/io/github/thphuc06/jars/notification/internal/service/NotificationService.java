package io.github.thphuc06.jars.notification.internal.service;

import io.github.thphuc06.jars.insights.AnomalyDetected;
import io.github.thphuc06.jars.notification.internal.model.Notification;
import io.github.thphuc06.jars.notification.internal.persistence.NotificationRepository;
import io.github.thphuc06.jars.platform.idempotency.ProcessedMessages;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.KeysetCursor;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.ProblemTypes;
import io.github.thphuc06.jars.shared.UserId;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Creates, lists and reads notifications, and pushes new ones to open SSE connections. */
@Service
public class NotificationService {

    public record NotificationPage(List<NotificationView> items, String nextCursor, long unreadCount) {}

    static final String CONSUMER = "notification.anomaly-detected";
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_REPLAY = 100;

    private final NotificationRepository notifications;
    private final ProcessedMessages inbox;
    private final SseRegistry sse;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, ProcessedMessages inbox, SseRegistry sse, Clock clock) {
        this.notifications = notifications;
        this.inbox = inbox;
        this.sse = sse;
        this.clock = clock;
    }

    /** Turns an anomaly into a notification, once per event, and pushes it to the user after the commit. */
    @Transactional
    void createFromAnomaly(AnomalyDetected event) {
        if (!inbox.firstTime(CONSUMER, event.eventId().toString())) {
            return;
        }
        Notification saved = notifications.save(new Notification(
                event.userId().value(),
                "ANOMALY",
                event.severity(),
                title(event),
                body(event),
                "ANOMALY",
                event.anomalyId(),
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
        NotificationView view = NotificationView.of(saved);
        // push only once the notification is durable, so a client that reacts by calling the API finds it
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sse.publish(event.userId(), view);
            }
        });
    }

    @Transactional(readOnly = true)
    public NotificationPage list(UserId userId, boolean unreadOnly, Integer limit, String cursor) {
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new DomainException(ProblemTypes.BAD_REQUEST, "limit phải từ 1 đến " + MAX_PAGE_SIZE + ".");
        }
        Limit fetch = Limit.of(pageSize + 1);
        List<Notification> rows;
        if (cursor == null || cursor.isBlank()) {
            rows = notifications.firstPage(userId.value(), unreadOnly, fetch);
        } else {
            KeysetCursor position = KeysetCursor.decode(cursor);
            rows = notifications.pageAfter(userId.value(), unreadOnly, position.at(), position.id(), fetch);
        }
        boolean hasMore = rows.size() > pageSize;
        List<Notification> page = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore
                ? new KeysetCursor(page.getLast().getCreatedAt(), page.getLast().getId()).encode()
                : null;
        return new NotificationPage(
                page.stream().map(NotificationView::of).toList(),
                next,
                notifications.countByUserIdAndReadAtIsNull(userId.value()));
    }

    /** Marks one of the user's own notifications as read; calling it again is harmless. */
    @Transactional
    public NotificationView markRead(UserId userId, UUID id) {
        Notification notification = notifications
                .findByIdAndUserId(id, userId.value())
                .orElseThrow(() -> DomainException.notFound("Thông báo"));
        // PostgreSQL keeps microseconds: truncate so the response equals what a later read returns
        notification.markRead(clock.instant().truncatedTo(ChronoUnit.MICROS));
        return NotificationView.of(notification);
    }

    /** What a reconnecting client missed: notifications newer than {@code lastEventId}; empty if the id is unusable. */
    @Transactional(readOnly = true)
    List<NotificationView> missedSince(UserId userId, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return List.of();
        }
        UUID after;
        try {
            after = UUID.fromString(lastEventId.trim());
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        return notifications
                .findByUserIdAndIdGreaterThanOrderByIdAsc(userId.value(), after, Limit.of(MAX_REPLAY))
                .stream()
                .map(NotificationView::of)
                .toList();
    }

    private static String title(AnomalyDetected e) {
        return switch (e.detector() + "/" + e.direction()) {
            case "MAD/UP" -> "Hũ " + e.jarName() + " có khoản chi bất thường";
            case "CUSUM/UP" -> "Hũ " + e.jarName() + " đang chi tăng liên tục";
            default -> "Hũ " + e.jarName() + " chi ít hơn thường lệ";
        };
    }

    private static String body(AnomalyDetected e) {
        return switch (e.detector() + "/" + e.direction()) {
            case "MAD/UP" ->
                "Khoản chi " + vnd(e.amount()) + " cao hơn nhiều so với các khoản chi thường lệ của hũ này.";
            case "CUSUM/UP" ->
                "Các khoản chi gần đây của hũ tăng đều so với mức thường lệ (khoản mới nhất " + vnd(e.amount()) + ").";
            default ->
                "Các khoản chi gần đây của hũ thấp hơn đều so với mức thường lệ (khoản mới nhất " + vnd(e.amount())
                        + ").";
        };
    }

    private static String vnd(Money amount) {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"));
        return format.format(amount.amount().setScale(0, java.math.RoundingMode.HALF_EVEN)) + " ₫";
    }
}
