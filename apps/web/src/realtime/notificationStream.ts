import { EventStreamContentType, fetchEventSource, type EventSourceMessage } from "@microsoft/fetch-event-source";
import type { Notification } from "@/api/types";

export const NOTIFICATION_STREAM_URL = "/api/v1/notifications/stream";

/**
 * Remembers the ids it has been offered. After a reconnect the server replays the notifications newer than
 * `Last-Event-ID`, which can include one that had already been delivered; it must reach the UI once.
 */
export class SeenIds {
  private readonly ids = new Set<string>();

  constructor(private readonly capacity = 500) {}

  /** True the first time `id` is offered, false for every repeat. */
  add(id: string): boolean {
    if (this.ids.has(id)) return false;
    this.ids.add(id);
    if (this.ids.size > this.capacity) {
      const oldest = this.ids.values().next().value;
      if (oldest !== undefined) this.ids.delete(oldest);
    }
    return true;
  }
}

function isNotification(value: unknown): value is Notification {
  if (typeof value !== "object" || value === null) return false;
  const v = value as Record<string, unknown>;
  return typeof v.id === "string" && typeof v.title === "string";
}

/** The `data:` of an `event: notification` frame, or null for any other frame or malformed JSON. */
export function parseNotificationFrame(frame: Pick<EventSourceMessage, "event" | "data">): Notification | null {
  if (frame.event !== "notification" || !frame.data) return null;
  try {
    const parsed: unknown = JSON.parse(frame.data);
    return isNotification(parsed) ? { ...parsed, readAt: parsed.readAt ?? null } : null;
  } catch {
    return null;
  }
}

/** Not worth retrying: the request itself is wrong (401, 403, 404...). */
class FatalStreamError extends Error {}
/** Worth retrying: the server closed the stream, or answered 5xx / 429. */
class RetriableStreamError extends Error {}

export interface NotificationStreamOptions {
  /** Called once per notification id, in arrival order. */
  onNotification: (notification: Notification) => void;
  /** A connection was (re)established. */
  onOpen?: () => void;
  /** HTTP 401 on connect: the token is refused. The stream stops; the auth layer starts a new login. */
  onUnauthorized: () => void;
  /** Resolves a fresh access token for EVERY (re)connect, so a long-lived stream survives token refreshes. */
  getAccessToken: () => Promise<string | undefined>;
  /** Abort on sign-out or unmount. */
  signal: AbortSignal;
  url?: string;
  /** For tests */
  fetch?: typeof fetch;
  /** Reconnect delay for attempt n (0-based). Default: 1 s doubling up to 30 s. */
  retryDelayMs?: (attempt: number) => number;
}

export const defaultRetryDelayMs = (attempt: number) => Math.min(30_000, 1_000 * 2 ** attempt);

/**
 * Subscribes to GET /api/v1/notifications/stream with `fetch` (the native EventSource cannot send the Authorization
 * header). The server sends `event: notification` frames, `: connected` on open and `: heartbeat` every 15 s (both
 * comments are ignored by the parser) and replays missed notifications when the client reconnects with
 * `Last-Event-ID`, which `fetchEventSource` sets from the `id:` of the last frame it saw.
 *
 * Resolves when `signal` is aborted or a fatal error stops the stream.
 */
export async function startNotificationStream(options: NotificationStreamOptions): Promise<void> {
  const { signal, getAccessToken, onNotification, onOpen, onUnauthorized } = options;
  const doFetch = options.fetch ?? ((...args: Parameters<typeof fetch>) => fetch(...args));
  const retryDelay = options.retryDelayMs ?? defaultRetryDelayMs;
  const seen = new SeenIds();
  let attempt = 0;

  try {
    await fetchEventSource(options.url ?? NOTIFICATION_STREAM_URL, {
      signal,
      // keep the stream (and the badge) alive in a background tab
      openWhenHidden: true,
      fetch: async (input, init) => {
        const token = await getAccessToken();
        const headers = new Headers(init?.headers);
        if (token) headers.set("Authorization", `Bearer ${token}`);
        return doFetch(input, { ...init, headers });
      },
      async onopen(response) {
        if (response.ok && response.headers.get("content-type")?.startsWith(EventStreamContentType)) {
          attempt = 0;
          onOpen?.();
          return;
        }
        if (response.status === 401) {
          onUnauthorized();
          throw new FatalStreamError("unauthorized");
        }
        if (response.status >= 400 && response.status < 500 && response.status !== 429) {
          throw new FatalStreamError(`stream refused: ${response.status}`);
        }
        throw new RetriableStreamError(`stream unavailable: ${response.status}`);
      },
      onmessage(frame) {
        const notification = parseNotificationFrame(frame);
        if (notification && seen.add(notification.id)) onNotification(notification);
      },
      onclose() {
        // the server ends a stream when its emitter times out: reconnect (Last-Event-ID replays what was missed)
        throw new RetriableStreamError("stream closed by the server");
      },
      onerror(error) {
        if (error instanceof FatalStreamError) throw error;
        return retryDelay(attempt++);
      },
    });
  } catch (error) {
    if (!(error instanceof FatalStreamError)) throw error;
  }
}
