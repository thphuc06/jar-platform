package io.github.thphuc06.jars.notification.internal.service;

import io.github.thphuc06.jars.budgeting.MonthClosed;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to {@link MonthClosed} from the budgeting module. It runs after budgeting's transaction committed, in its own
 * transaction, and stays in the event publication registry until it succeeds.
 */
@Component
class MonthClosedNotifier {

    private final NotificationService notifications;

    MonthClosedNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    void on(MonthClosed event) {
        notifications.createFromMonthClosed(event);
    }
}
