import createClient from "openapi-fetch";
import { getAccessToken, notifySessionExpired } from "@/auth/session";
import type { BudgetPaths } from "./budget";
import type { GoalsPaths } from "./goals";
import { createAuthMiddleware, type AuthMiddlewareOptions } from "./middleware";
import type { paths } from "./schema";

/**
 * TODO(WP5-openapi): `GoalsPaths` and `BudgetPaths` are hand-written (goals.ts, budget.ts) because the endpoints are not
 * in the OpenAPI file yet. When they are, regenerate `schema.d.ts` and make this just `paths`.
 */
type ApiPaths = paths & GoalsPaths & BudgetPaths;

export interface ApiClientOptions extends AuthMiddlewareOptions {
  /** Empty in the app: every URL is relative, so the browser stays on one origin (Vite proxy, CloudFront). */
  baseUrl?: string;
  fetch?: (input: Request) => Promise<Response>;
}

export function createApiClient({ baseUrl = "", fetch, ...auth }: ApiClientOptions) {
  const client = createClient<ApiPaths>({ baseUrl, fetch });
  client.use(createAuthMiddleware(auth));
  return client;
}

export const api = createApiClient({ getAccessToken, onUnauthorized: notifySessionExpired });
