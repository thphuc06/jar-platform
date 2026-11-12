import { describe, expect, it } from "vitest";
import { addIncoming, applyRead, isUnread, type NotificationsData } from "./notifications";
import type { Notification, NotificationPage } from "./types";

const notification = (id: string, readAt: string | null = null): Notification => ({
  id,
  type: "ANOMALY",
  severity: "HIGH",
  title: `title ${id}`,
  body: null,
  refType: "ANOMALY",
  refId: null,
  readAt,
  createdAt: "2026-11-15T03:21:09Z",
});

const data = (pages: NotificationPage[]): NotificationsData => ({
  pages,
  pageParams: pages.map((_, i) => (i === 0 ? undefined : `cursor-${i}`)),
});

const page = (ids: string[], unreadCount: number, nextCursor: string | null = null): NotificationPage => ({
  items: ids.map((id) => notification(id)),
  nextCursor,
  unreadCount,
});

describe("addIncoming (notification de-duplication in the cache)", () => {
  it("puts a new notification at the head of the first page and raises the unread count", () => {
    const { data: next, added } = addIncoming(data([page(["n2", "n1"], 2)]), notification("n3"));
    expect(added).toBe(true);
    expect(next?.pages[0].items.map((n) => n.id)).toEqual(["n3", "n2", "n1"]);
    expect(next?.pages[0].unreadCount).toBe(3);
  });

  it("ignores a notification that is already in the cache (a reconnect replayed it)", () => {
    const current = data([page(["n2", "n1"], 2)]);
    const { data: next, added } = addIncoming(current, notification("n2"));
    expect(added).toBe(false);
    expect(next).toBe(current);
  });

  it("also finds it on a later page", () => {
    const current = data([page(["n3"], 3, "c"), page(["n2", "n1"], 3)]);
    expect(addIncoming(current, notification("n1")).added).toBe(false);
  });

  it("does not raise the counter for a notification that is already read", () => {
    const { data: next } = addIncoming(data([page(["n1"], 1)]), notification("n2", "2026-11-15T04:00:00Z"));
    expect(next?.pages[0].unreadCount).toBe(1);
  });

  it("reports added when the list is not loaded yet, so the caller refetches", () => {
    expect(addIncoming(undefined, notification("n1"))).toEqual({ data: undefined, added: true });
  });

  it("applying the same notification twice changes the cache once", () => {
    const once = addIncoming(data([page(["n1"], 1)]), notification("n2")).data;
    const twice = addIncoming(once, notification("n2"));
    expect(twice.added).toBe(false);
    expect(twice.data?.pages[0].items.map((n) => n.id)).toEqual(["n2", "n1"]);
    expect(twice.data?.pages[0].unreadCount).toBe(2);
  });
});

describe("applyRead", () => {
  it("marks the notification read and lowers the unread count once", () => {
    const read = notification("n1", "2026-11-15T05:00:00Z");
    const next = applyRead(data([page(["n2", "n1"], 2)]), read);
    expect(next?.pages[0].items.find((n) => n.id === "n1")?.readAt).toBe("2026-11-15T05:00:00Z");
    expect(next?.pages[0].unreadCount).toBe(1);
    // reading it again does not lower the counter a second time
    expect(applyRead(next, read)?.pages[0].unreadCount).toBe(1);
  });

  it("never goes below zero and tolerates an empty cache", () => {
    expect(applyRead(data([page(["n1"], 0)]), notification("n1", "t"))?.pages[0].unreadCount).toBe(0);
    expect(applyRead(undefined, notification("n1", "t"))).toBeUndefined();
  });
});

describe("isUnread", () => {
  it("treats null and a dropped readAt as unread", () => {
    expect(isUnread(notification("a"))).toBe(true);
    expect(isUnread({ ...notification("a"), readAt: undefined as unknown as null })).toBe(true);
    expect(isUnread(notification("a", "2026-11-15T05:00:00Z"))).toBe(false);
  });
});
