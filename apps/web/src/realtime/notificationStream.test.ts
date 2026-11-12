import { describe, expect, it, vi } from "vitest";
import type { Notification } from "@/api/types";
import { parseNotificationFrame, SeenIds, startNotificationStream } from "./notificationStream";

const frame = (id: string, title = `Thông báo ${id}`) =>
  `id: ${id}\nevent: notification\ndata: ${JSON.stringify({
    id,
    type: "ANOMALY",
    severity: "HIGH",
    title,
    refType: "ANOMALY",
    refId: "11111111-1111-4111-8111-111111111111",
  })}\n\n`;

/** One SSE response whose body is the given text, then ends (as when the server closes the emitter). */
function sseResponse(text: string, status = 200, contentType = "text/event-stream;charset=UTF-8") {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(text));
      controller.close();
    },
  });
  return new Response(status === 200 ? body : null, { status, headers: { "content-type": contentType } });
}

describe("SeenIds", () => {
  it("accepts an id once", () => {
    const seen = new SeenIds();
    expect(seen.add("a")).toBe(true);
    expect(seen.add("a")).toBe(false);
    expect(seen.add("b")).toBe(true);
  });

  it("forgets the oldest id beyond its capacity", () => {
    const seen = new SeenIds(2);
    seen.add("a");
    seen.add("b");
    seen.add("c");
    expect(seen.add("b")).toBe(false);
    expect(seen.add("a")).toBe(true);
  });
});

describe("parseNotificationFrame", () => {
  it("reads a notification event", () => {
    const parsed = parseNotificationFrame({ event: "notification", data: JSON.stringify({ id: "n1", title: "T" }) });
    expect(parsed).toMatchObject({ id: "n1", title: "T", readAt: null });
  });

  it("ignores other events, empty data and malformed JSON", () => {
    expect(parseNotificationFrame({ event: "ping", data: "{}" })).toBeNull();
    expect(parseNotificationFrame({ event: "notification", data: "" })).toBeNull();
    expect(parseNotificationFrame({ event: "notification", data: "{oops" })).toBeNull();
    expect(parseNotificationFrame({ event: "notification", data: JSON.stringify({ title: "no id" }) })).toBeNull();
  });
});

describe("startNotificationStream", () => {
  it("sends the bearer token, reconnects with Last-Event-ID and delivers a replayed notification once", async () => {
    const calls: Array<{ url: string; headers: Headers }> = [];
    const responses = [
      // first connection: comment, one notification, then the server closes the stream
      sseResponse(`: connected\n\n${frame("n1")}: heartbeat\n\n`),
      // second connection (after the reconnect): the server replays n1 and adds n2
      sseResponse(`: connected\n\n${frame("n1")}${frame("n2")}`),
    ];
    let tokens = 0;
    const controller = new AbortController();
    const received: Notification[] = [];
    const onOpen = vi.fn();

    const done = startNotificationStream({
      signal: controller.signal,
      getAccessToken: async () => `access-${++tokens}`,
      onUnauthorized: vi.fn(),
      onNotification: (n) => {
        received.push(n);
        if (received.length === 2) controller.abort();
      },
      onOpen,
      retryDelayMs: () => 0,
      fetch: async (input, init) => {
        calls.push({ url: String(input), headers: new Headers(init?.headers) });
        const next = responses.shift();
        if (!next) return new Promise<Response>(() => {});
        return next;
      },
    });

    await vi.waitFor(() => expect(received).toHaveLength(2));
    await done;

    expect(received.map((n) => n.id)).toEqual(["n1", "n2"]);
    expect(onOpen).toHaveBeenCalledTimes(2);
    expect(calls).toHaveLength(2);
    expect(calls[0].url).toBe("/api/v1/notifications/stream");
    expect(calls[0].headers.get("authorization")).toBe("Bearer access-1");
    expect(calls[0].headers.get("accept")).toBe("text/event-stream");
    expect(calls[0].headers.has("last-event-id")).toBe(false);
    // the reconnect asks to replay everything after the last id it saw, with a freshly resolved token
    expect(calls[1].headers.get("last-event-id")).toBe("n1");
    expect(calls[1].headers.get("authorization")).toBe("Bearer access-2");
  });

  it("stops on a 401 without retrying and tells the auth layer", async () => {
    const onUnauthorized = vi.fn();
    const fetchSpy = vi.fn(async () => sseResponse("", 401, "application/problem+json"));

    await startNotificationStream({
      signal: new AbortController().signal,
      getAccessToken: async () => "expired",
      onUnauthorized,
      onNotification: vi.fn(),
      retryDelayMs: () => 0,
      fetch: fetchSpy,
    });

    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
  });

  it("retries after a 503 and then delivers", async () => {
    const controller = new AbortController();
    const received: Notification[] = [];
    const responses = [sseResponse("", 503, "text/plain"), sseResponse(frame("n9"))];

    const done = startNotificationStream({
      signal: controller.signal,
      getAccessToken: async () => "t",
      onUnauthorized: vi.fn(),
      onNotification: (n) => {
        received.push(n);
        controller.abort();
      },
      retryDelayMs: () => 0,
      fetch: async () => responses.shift() ?? new Promise<Response>(() => {}),
    });

    await vi.waitFor(() => expect(received).toHaveLength(1));
    await done;
    expect(received[0].id).toBe("n9");
  });

  it("stops when the signal is aborted (sign-out) and does not open another connection", async () => {
    const controller = new AbortController();
    const fetchSpy = vi.fn(async () => sseResponse(": connected\n\n"));
    const done = startNotificationStream({
      signal: controller.signal,
      getAccessToken: async () => "t",
      onUnauthorized: vi.fn(),
      onNotification: vi.fn(),
      retryDelayMs: () => 20,
      fetch: fetchSpy,
    });
    await vi.waitFor(() => expect(fetchSpy).toHaveBeenCalled());
    controller.abort();
    await done;
    const callsAtAbort = fetchSpy.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 80));
    expect(fetchSpy.mock.calls.length).toBe(callsAtAbort);
  });
});
