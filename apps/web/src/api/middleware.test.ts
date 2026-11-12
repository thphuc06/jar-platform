import { describe, expect, it, vi } from "vitest";
import { createApiClient } from "./client";
import { ApiError } from "./problem";

function setup(options: { token?: string | undefined; respond: (request: Request) => Response | Promise<Response> }) {
  const requests: Request[] = [];
  const onUnauthorized = vi.fn();
  const api = createApiClient({
    baseUrl: "http://localhost",
    fetch: async (request) => {
      requests.push(request);
      return options.respond(request);
    },
    getAccessToken: async () => options.token,
    onUnauthorized,
  });
  return { api, requests, onUnauthorized };
}

const json = (body: unknown, init: ResponseInit = {}) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" }, ...init });

describe("auth middleware", () => {
  it("adds the access token as a bearer header", async () => {
    const { api, requests } = setup({ token: "access-token-1", respond: () => json({ status: "UP" }) });

    const { data } = await api.GET("/api/v1/health");

    expect(data).toEqual({ status: "UP" });
    expect(requests[0].headers.get("authorization")).toBe("Bearer access-token-1");
  });

  it("asks for the token on every request, so a refreshed token is used", async () => {
    let n = 0;
    const requests: Request[] = [];
    const api = createApiClient({
      baseUrl: "http://localhost",
      fetch: async (request) => {
        requests.push(request);
        return json({});
      },
      getAccessToken: async () => `token-${++n}`,
      onUnauthorized: () => {},
    });

    await api.GET("/api/v1/health");
    await api.GET("/api/v1/health");

    expect(requests.map((r) => r.headers.get("authorization"))).toEqual(["Bearer token-1", "Bearer token-2"]);
  });

  it("sends no Authorization header when nobody is signed in", async () => {
    const { api, requests } = setup({ token: undefined, respond: () => json({}) });
    await api.GET("/api/v1/health");
    expect(requests[0].headers.has("authorization")).toBe(false);
  });

  it("sends the Idempotency-Key header and the JSON body of a money POST", async () => {
    const { api, requests } = setup({ token: "t", respond: () => json({ transaction: {}, jarBalances: [] }) });

    await api.POST("/api/v1/transactions", {
      params: { header: { "Idempotency-Key": "7f9c0d2e-0000-4000-8000-000000000001" } },
      body: { type: "EXPENSE", amount: "85000" as unknown as number },
    });

    expect(requests[0].method).toBe("POST");
    expect(requests[0].headers.get("idempotency-key")).toBe("7f9c0d2e-0000-4000-8000-000000000001");
    expect(requests[0].headers.get("content-type")).toBe("application/json");
    expect(await requests[0].json()).toEqual({ type: "EXPENSE", amount: "85000" });
  });

  it("turns problem+json into a typed ApiError that rejects the call", async () => {
    const { api } = setup({
      token: "t",
      respond: () =>
        new Response(
          JSON.stringify({
            type: "https://jars.local/problems/insufficient-jar-balance",
            title: "Số dư hũ không đủ",
            status: 422,
            detail: "Hũ PLAY còn 120000.00 VND",
            traceId: "abc123",
            errors: [{ field: "amount", message: "vượt số dư" }],
          }),
          { status: 422, headers: { "content-type": "application/problem+json" } },
        ),
    });

    const failure = await api.GET("/api/v1/jars").then(
      () => null,
      (error: unknown) => error,
    );

    expect(failure).toBeInstanceOf(ApiError);
    const error = failure as ApiError;
    expect(error.status).toBe(422);
    expect(error.code).toBe("insufficient-jar-balance");
    expect(error.traceId).toBe("abc123");
    expect(error.errors).toEqual([{ field: "amount", message: "vượt số dư" }]);
  });

  it("calls onUnauthorized on a 401 and still rejects with the ApiError", async () => {
    const { api, onUnauthorized } = setup({
      token: "expired",
      respond: () =>
        new Response(JSON.stringify({ title: "Unauthorized", status: 401 }), {
          status: 401,
          headers: { "content-type": "application/problem+json" },
        }),
    });

    await expect(api.GET("/api/v1/me")).rejects.toMatchObject({ name: "ApiError", status: 401 });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it("does not treat 403 or 404 as a reason to sign in again", async () => {
    const { api, onUnauthorized } = setup({ token: "t", respond: () => new Response("{}", { status: 404 }) });
    await expect(api.GET("/api/v1/me")).rejects.toMatchObject({ status: 404 });
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("maps a non-JSON gateway error to an ApiError", async () => {
    const { api } = setup({
      token: "t",
      respond: () => new Response("<html>502</html>", { status: 502, headers: { "content-type": "text/html" } }),
    });
    await expect(api.GET("/api/v1/me")).rejects.toMatchObject({ name: "ApiError", status: 502 });
  });

  it("passes network failures through unchanged", async () => {
    const { api } = setup({
      token: "t",
      respond: () => {
        throw new TypeError("Failed to fetch");
      },
    });
    await expect(api.GET("/api/v1/me")).rejects.toBeInstanceOf(TypeError);
  });
});
