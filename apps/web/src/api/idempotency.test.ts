import { renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiError } from "./client";
import { retryIdempotent, useIdempotencyKey } from "./idempotency";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe("useIdempotencyKey", () => {
  it("keeps the key while the same request is retried, and changes it with the request or after a success", () => {
    const { result } = renderHook(() => useIdempotencyKey());
    const body = { type: "EXPENSE", amount: "85000" };

    const first = result.current.keyFor(body);
    expect(first).toMatch(UUID);
    expect(result.current.keyFor({ ...body })).toBe(first); // same content, e.g. Save pressed again after an error

    const edited = result.current.keyFor({ ...body, amount: "90000" });
    expect(edited).not.toBe(first); // reusing it would be 422 idempotency-key-reused

    result.current.done();
    expect(result.current.keyFor({ ...body, amount: "90000" })).not.toBe(edited); // a new action
  });
});

describe("retryIdempotent", () => {
  const problem = (status: number) => new ApiError(status, { type: "x", title: "t", status });

  it("retries a lost response and a 5xx, twice at most", () => {
    expect(retryIdempotent(0, new TypeError("Failed to fetch"))).toBe(true);
    expect(retryIdempotent(1, problem(503))).toBe(true);
    expect(retryIdempotent(2, new TypeError("Failed to fetch"))).toBe(false);
  });

  it("never retries a 4xx: the same request gets the same answer", () => {
    expect(retryIdempotent(0, problem(422))).toBe(false);
    expect(retryIdempotent(0, problem(409))).toBe(false);
  });
});
