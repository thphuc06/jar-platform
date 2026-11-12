/**
 * Dev-token mode: local work without Cognito. The backend (`local` profile) mints an access token for any subject
 * at POST /dev/token. This module is only reachable behind `import.meta.env.DEV` checks, so it is not part of a
 * production build.
 */
import { toApiError } from "@/api/problem";

interface DevTokenResponse {
  accessToken: string;
  expiresIn: number;
}

interface Cached {
  sub: string;
  token: string;
  expiresAt: number;
}

/** Ask for a new token this long before the old one expires. */
const REFRESH_MARGIN_MS = 60_000;

let cached: Cached | undefined;
let pending: Promise<string> | undefined;

export interface DevTokenDeps {
  fetchImpl?: typeof fetch;
  now?: () => number;
}

export async function getDevAccessToken(sub: string, deps: DevTokenDeps = {}): Promise<string> {
  const now = deps.now ?? Date.now;
  if (cached && cached.sub === sub && cached.expiresAt - REFRESH_MARGIN_MS > now()) return cached.token;
  // several requests start at once on page load: they share one POST
  pending ??= requestToken(sub, deps).finally(() => {
    pending = undefined;
  });
  return pending;
}

async function requestToken(sub: string, deps: DevTokenDeps): Promise<string> {
  const doFetch = deps.fetchImpl ?? fetch;
  const now = deps.now ?? Date.now;
  const response = await doFetch("/dev/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sub }),
  });
  if (!response.ok) throw await toApiError(response);
  const body = (await response.json()) as DevTokenResponse;
  cached = { sub, token: body.accessToken, expiresAt: now() + body.expiresIn * 1000 };
  return body.accessToken;
}

/** After a 401 the cached token is useless (the backend restarted with another key, or it expired early). */
export function invalidateDevToken(): void {
  cached = undefined;
}
