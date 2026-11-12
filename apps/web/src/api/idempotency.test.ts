import { renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { IntentKey, useIntentKey, uuidv4 } from "./idempotency";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function counter() {
  let n = 0;
  return () => `key-${++n}`;
}

describe("IntentKey", () => {
  it("returns the same key while the same intent is retried", () => {
    const intent = new IntentKey(counter());
    const first = intent.get("expense|85000|jar-1");
    expect(intent.get("expense|85000|jar-1")).toBe(first);
    expect(intent.get("expense|85000|jar-1")).toBe(first);
  });

  it("makes a new key when the user changed the request (same key would be 422 idempotency-key-reused)", () => {
    const intent = new IntentKey(counter());
    const first = intent.get("expense|85000|jar-1");
    const second = intent.get("expense|90000|jar-1");
    expect(second).not.toBe(first);
    // and the new intent is stable again
    expect(intent.get("expense|90000|jar-1")).toBe(second);
  });

  it("makes a new key for the next intent after clear() (the previous one succeeded)", () => {
    const intent = new IntentKey(counter());
    const first = intent.get("same form values");
    intent.clear();
    expect(intent.get("same form values")).not.toBe(first);
  });

  it("generates UUID v4 keys by default", () => {
    expect(new IntentKey().get("x")).toMatch(UUID_V4);
  });
});

describe("useIntentKey", () => {
  it("keeps one IntentKey (and so one key) across re-renders of a form", () => {
    const { result, rerender } = renderHook(() => useIntentKey());
    const key = result.current.get("form");
    rerender();
    rerender();
    expect(result.current.get("form")).toBe(key);
  });
});

describe("uuidv4", () => {
  it("uses crypto.randomUUID when it exists", () => {
    expect(uuidv4({ randomUUID: () => "fixed" } as unknown as Crypto)).toBe("fixed");
  });

  it("builds a valid v4 UUID from getRandomValues where randomUUID is missing (http, non-secure context)", () => {
    const fake = {
      getRandomValues: (bytes: Uint8Array) => {
        bytes.fill(0xff);
        return bytes;
      },
    } as unknown as Crypto;
    const id = uuidv4(fake);
    expect(id).toMatch(UUID_V4);
    expect(id).toBe("ffffffff-ffff-4fff-bfff-ffffffffffff");
  });

  it("does not repeat", () => {
    const ids = new Set(Array.from({ length: 200 }, () => uuidv4()));
    expect(ids.size).toBe(200);
  });
});
