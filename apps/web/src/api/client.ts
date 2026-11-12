import createClient from "openapi-fetch";
import { getAccessToken, notifySessionExpired } from "@/auth/session";
import { createAuthMiddleware, type AuthMiddlewareOptions } from "./middleware";
import type { paths } from "./schema";

export interface ApiClientOptions extends AuthMiddlewareOptions {
  /** Empty in the app: every URL is relative, so the browser stays on one origin (Vite proxy, CloudFront). */
  baseUrl?: string;
  fetch?: (input: Request) => Promise<Response>;
}

export function createApiClient({ baseUrl = "", fetch, ...auth }: ApiClientOptions) {
  const client = createClient<paths>({ baseUrl, fetch });
  client.use(createAuthMiddleware(auth));
  return client;
}

export const api = createApiClient({ getAccessToken, onUnauthorized: notifySessionExpired });
