import { describe, expect, it } from "vitest";
import { ApiError } from "./problem";
import { shouldRetry } from "./queryClient";

describe("shouldRetry", () => {
  it("retries network errors and 5xx twice", () => {
    expect(shouldRetry(0, new TypeError("Failed to fetch"))).toBe(true);
    expect(shouldRetry(1, new ApiError(503))).toBe(true);
    expect(shouldRetry(2, new ApiError(503))).toBe(false);
  });

  it("does not retry an answer that will not change", () => {
    for (const status of [400, 401, 403, 404, 409, 422]) {
      expect(shouldRetry(0, new ApiError(status))).toBe(false);
    }
  });

  it("retries a timeout and a rate limit", () => {
    expect(shouldRetry(0, new ApiError(408))).toBe(true);
    expect(shouldRetry(0, new ApiError(429))).toBe(true);
  });
});
