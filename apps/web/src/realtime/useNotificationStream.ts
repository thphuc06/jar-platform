import { useEffect } from "react";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { addIncoming, notificationsKey, type NotificationsData } from "@/api/notifications";
import { qk } from "@/api/keys";
import type { Notification } from "@/api/types";
import { getAccessToken, notifySessionExpired } from "@/auth/session";
import { toast } from "@/hooks/use-toast";
import { startNotificationStream } from "./notificationStream";

/** Puts a streamed notification into the list/badge cache. Returns false when it was already there. */
export function applyIncomingNotification(queryClient: QueryClient, notification: Notification): boolean {
  const current = queryClient.getQueryData<NotificationsData>(notificationsKey);
  const { data, added } = addIncoming(current, notification);
  if (!added) return false;
  if (data) queryClient.setQueryData(notificationsKey, data);
  else void queryClient.invalidateQueries({ queryKey: notificationsKey });
  if (notification.type === "ANOMALY") {
    void queryClient.invalidateQueries({ queryKey: qk.anomalies });
    void queryClient.invalidateQueries({ queryKey: qk.dashboard });
  }
  return true;
}

/**
 * Keeps one notification stream open while `enabled` (signed in). A toast is shown and the badge and list are updated
 * for each new notification; the stream stops on sign-out (enabled -> false) and on unmount.
 */
export function useNotificationStream(enabled: boolean): void {
  const queryClient = useQueryClient();

  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    void startNotificationStream({
      signal: controller.signal,
      getAccessToken,
      onUnauthorized: notifySessionExpired,
      onNotification: (notification) => {
        if (!applyIncomingNotification(queryClient, notification)) return;
        toast({
          title: notification.title,
          description: notification.body ?? undefined,
          variant: notification.severity === "HIGH" ? "destructive" : "default",
        });
      },
    }).catch(() => {
      // a non-fatal error already retried inside; whatever reaches here (aborted fetch) needs no UI
    });
    return () => controller.abort();
  }, [enabled, queryClient]);
}
