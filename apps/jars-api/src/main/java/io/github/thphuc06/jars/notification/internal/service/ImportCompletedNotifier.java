package io.github.thphuc06.jars.notification.internal.service;

import io.github.thphuc06.jars.imports.StatementImportCompleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Reacts to {@link StatementImportCompleted} after the import's transaction committed, through the outbox. */
@Component
class ImportCompletedNotifier {

    private final NotificationService notifications;

    ImportCompletedNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    void on(StatementImportCompleted event) {
        notifications.createFromImport(event);
    }
}
