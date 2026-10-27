import { User } from "oidc-client-ts";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, createApiClient, unwrap } from "./client";

const user = (expiresInSeconds: number) =>
  new User({
    access_token: "ACCESS",
    id_token: "ID",
    token_type: "Bearer",
    profile: { sub: "s", iss: "i", aud: "a", exp: 0, iat: 0 },
    expires_at: Math.floor(Date.now() / 1000) + expiresInSeconds,
  });

function stubFetch(status: number, body: unknown, contentType = "application/json") {
  const fetch = vi.fn(async (_request: Request) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": contentType } }),
  );
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

afterEach(() => vi.unstubAllGlobals());

describe("api client", () => {
  it("sends the ACCESS token (not the ID token) as a bearer token", async () => {
    const fetch = stubFetch(200, { status: "UP", service: "jars-api", version: "dev" });
    const api = createApiClient(async () => user(3600));

    await unwrap(api.GET("/api/v1/health"));

    const request = fetch.mock.calls[0][0];
    expect(request.headers.get("Authorization")).toBe("Bearer ACCESS");
    expect(new URL(request.url).pathname).toBe("/api/v1/health");
  });

  it("sends no Authorization header when signed out or when the token has expired", async () => {
    const fetch = stubFetch(200, {});
    await unwrap(createApiClient(async () => null).GET("/api/v1/health"));
    await unwrap(createApiClient(async () => user(-10)).GET("/api/v1/health"));

    expect(fetch.mock.calls.map(([request]) => request.headers.has("Authorization"))).toEqual([false, false]);
  });

  it("turns an RFC 9457 problem into an ApiError carrying the stable code", async () => {
    stubFetch(
      422,
      {
        type: "https://jars.local/problems/insufficient-jar-balance",
        title: "Unprocessable Content",
        status: 422,
        detail: "Jar NEC holds 0 VND, 50000 VND requested",
        traceId: "4bf92f35",
      },
      "application/problem+json",
    );
    const api = createApiClient(async () => user(3600));

    const error = await unwrap(
      api.POST("/api/v1/transactions", {
        params: { header: { "Idempotency-Key": crypto.randomUUID() } },
        body: { type: "EXPENSE", amount: "50000", jarId: crypto.randomUUID() },
      }),
    ).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 422, code: "insufficient-jar-balance", message: expect.stringContaining("holds 0") });
    expect((error as ApiError).problem?.traceId).toBe("4bf92f35");
  });
});
