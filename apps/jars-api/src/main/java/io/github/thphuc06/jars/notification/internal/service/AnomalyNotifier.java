package io.github.thphuc06.jars.notification.internal.service;

import io.github.thphuc06.jars.insights.AnomalyDetected;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to {@link AnomalyDetected} from the insights module. It runs after insights' transaction committed, in its own
 * transaction, and stays in the event publication registry until it succeeds.
 */
@Component
class AnomalyNotifier {

    private final NotificationService notifications;

    AnomalyNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    void on(AnomalyDetected event) {
        notifications.createFromAnomaly(event);
    }
}
