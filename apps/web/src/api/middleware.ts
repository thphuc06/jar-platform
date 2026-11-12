import type { Middleware } from "openapi-fetch";
import { toApiError } from "./problem";

export interface AuthMiddlewareOptions {
  /** Resolves the ACCESS token (never the ID token), or undefined when nobody is signed in. */
  getAccessToken: () => Promise<string | undefined>;
  /** HTTP 401: the token was refused. The auth layer decides (sign in again). */
  onUnauthorized: () => void;
}

/**
 * - adds `Authorization: Bearer <access token>` to every request,
 * - on HTTP 401 tells the auth layer to start a new login,
 * - turns every non-2xx response (RFC 9457 problem+json) into a typed {@link ApiError} that rejects the call, so
 *   TanStack Query sees a failure and `data` of a resolved call is always the success body.
 */
export function createAuthMiddleware({ getAccessToken, onUnauthorized }: AuthMiddlewareOptions): Middleware {
  return {
    async onRequest({ request }) {
      const token = await getAccessToken();
      if (token) request.headers.set("Authorization", `Bearer ${token}`);
      return request;
    },
    async onResponse({ response }) {
      if (response.ok) return undefined;
      if (response.status === 401) onUnauthorized();
      throw await toApiError(response);
    },
  };
}
