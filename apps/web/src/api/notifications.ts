import { useInfiniteQuery, useMutation, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import { api } from "./client";
import { unwrap } from "./http";
import { qk } from "./keys";
import { nextCursor } from "./paging";
import type { Notification, NotificationPage } from "./types";

const PAGE_SIZE = 20;

export type NotificationsData = InfiniteData<NotificationPage, string | undefined>;

/** Cache key of the notification list; the realtime stream writes into the same entry. */
export const notificationsKey = [...qk.notifications, "list"] as const;

/** Notification list (newest first). The first page also carries `unreadCount`, which drives the nav badge. */
export function useNotifications() {
  return useInfiniteQuery({
    queryKey: notificationsKey,
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      unwrap<NotificationPage>(
        api.GET("/api/v1/notifications", { params: { query: { limit: PAGE_SIZE, cursor: pageParam } }, signal }),
      ),
    getNextPageParam: nextCursor,
  });
}

/** `readAt` is null (or absent, if the server drops nulls) until the notification has been read. */
export function isUnread(notification: Notification): boolean {
  return notification.readAt == null;
}

/** Unread notifications of the user (not just the loaded page), 0 until the first page has arrived. */
export function useUnreadCount(): number {
  return useNotifications().data?.pages[0]?.unreadCount ?? 0;
}

/**
 * A notification that arrived on the stream, added to the head of the first cached page. Returns the same data and
 * `added: false` when the notification is already in the cache: a reconnect with Last-Event-ID can replay one that the
 * list request has already returned.
 */
export function addIncoming(
  data: NotificationsData | undefined,
  incoming: Notification,
): { data: NotificationsData | undefined; added: boolean } {
  if (!data || data.pages.length === 0) return { data, added: true };
  if (data.pages.some((page) => page.items.some((n) => n.id === incoming.id))) return { data, added: false };
  const [first, ...rest] = data.pages;
  return {
    added: true,
    data: {
      ...data,
      pages: [
        {
          ...first,
          items: [incoming, ...first.items],
          unreadCount: first.unreadCount + (isUnread(incoming) ? 1 : 0),
        },
        ...rest,
      ],
    },
  };
}

/** The server's updated notification (now read) written into the cache, and the unread counter lowered once. */
export function applyRead(data: NotificationsData | undefined, updated: Notification): NotificationsData | undefined {
  if (!data) return data;
  const previous = data.pages.flatMap((p) => p.items).find((n) => n.id === updated.id);
  const wasUnread = previous !== undefined && isUnread(previous) && !isUnread(updated);
  return {
    ...data,
    pages: data.pages.map((page, index) => ({
      ...page,
      items: page.items.map((n) => (n.id === updated.id ? updated : n)),
      unreadCount: index === 0 && wasUnread ? Math.max(0, page.unreadCount - 1) : page.unreadCount,
    })),
  };
}

export function useMarkNotificationRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      unwrap<Notification>(api.POST("/api/v1/notifications/{id}/read", { params: { path: { id } } })),
    onSuccess: (updated) =>
      queryClient.setQueryData<NotificationsData>(notificationsKey, (data) => applyRead(data, updated)),
  });
}
