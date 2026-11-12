import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/problem";
import { getDevAccessToken, invalidateDevToken } from "./devToken";

function tokenServer(expiresIn = 3600) {
  let issued = 0;
  const fetchImpl = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
    const { sub } = JSON.parse(String(init?.body)) as { sub: string };
    issued++;
    return new Response(JSON.stringify({ accessToken: `token-${sub}-${issued}`, expiresIn }), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  });
  return fetchImpl;
}

describe("dev-token mode", () => {
  beforeEach(() => invalidateDevToken());

  it("asks POST /dev/token with the subject and returns the access token", async () => {
    const fetchImpl = tokenServer();
    const token = await getDevAccessToken("alice", { fetchImpl });

    expect(token).toBe("token-alice-1");
    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toBe("/dev/token");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({ sub: "alice" });
  });

  it("reuses the token until shortly before it expires", async () => {
    const fetchImpl = tokenServer(3600);
    let now = 1_000_000;
    const deps = { fetchImpl, now: () => now };

    const first = await getDevAccessToken("alice", deps);
    now += 30 * 60_000;
    expect(await getDevAccessToken("alice", deps)).toBe(first);
    expect(fetchImpl).toHaveBeenCalledTimes(1);

    now += 29.5 * 60_000; // inside the one-minute margin before expiry
    expect(await getDevAccessToken("alice", deps)).not.toBe(first);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("shares one request between simultaneous callers (page load fires several queries)", async () => {
    const fetchImpl = tokenServer();
    const tokens = await Promise.all([
      getDevAccessToken("alice", { fetchImpl }),
      getDevAccessToken("alice", { fetchImpl }),
      getDevAccessToken("alice", { fetchImpl }),
    ]);
    expect(new Set(tokens).size).toBe(1);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("fetches a new token for a different subject or after invalidation (after a 401)", async () => {
    const fetchImpl = tokenServer();
    const a = await getDevAccessToken("alice", { fetchImpl });
    const b = await getDevAccessToken("bob", { fetchImpl });
    expect(b).not.toBe(a);

    invalidateDevToken();
    expect(await getDevAccessToken("bob", { fetchImpl })).not.toBe(b);
  });

  it("surfaces the backend's problem when the endpoint is missing or refuses", async () => {
    const fetchImpl = vi.fn(
      async () =>
        new Response(JSON.stringify({ title: "Not Found", status: 404 }), {
          status: 404,
          headers: { "content-type": "application/problem+json" },
        }),
    );
    await expect(getDevAccessToken("alice", { fetchImpl })).rejects.toBeInstanceOf(ApiError);
    // a failed attempt must not leave a half-finished request behind
    await expect(getDevAccessToken("alice", { fetchImpl })).rejects.toBeInstanceOf(ApiError);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });
});
