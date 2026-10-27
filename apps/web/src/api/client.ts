import createClient, { type Middleware } from "openapi-fetch";
import type { User } from "oidc-client-ts";
import type { components, paths } from "./schema";

export type Problem = components["schemas"]["Problem"];

/** Any non-2xx answer of jars-api: the RFC 9457 problem plus the HTTP status. `code` is the stable error id. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | undefined;

  constructor(status: number, problem: Problem | undefined) {
    super(problem?.detail ?? problem?.title ?? `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }

  /** "insufficient-jar-balance" from "https://jars.local/problems/insufficient-jar-balance". */
  get code(): string | undefined {
    return this.problem?.type.split("/").pop();
  }
}

/** Adds the Cognito ACCESS token (never the ID token: the API rejects it) to every request. */
export function bearerToken(getUser: () => Promise<User | null>): Middleware {
  return {
    async onRequest({ request }) {
      const user = await getUser();
      if (user && !user.expired) {
        request.headers.set("Authorization", `Bearer ${user.access_token}`);
      }
      return request;
    },
  };
}

export function createApiClient(getUser: () => Promise<User | null>, baseUrl = globalThis.location.origin) {
  const client = createClient<paths>({
    // same origin: the Vite proxy locally, CloudFront /api/* in AWS (no CORS either way)
    baseUrl,
    // look fetch up per call: openapi-fetch would otherwise keep the one that existed when the client was created
    fetch: (request) => globalThis.fetch(request),
  });
  client.use(bearerToken(getUser));
  return client;
}

/** openapi-fetch returns {data, error, response}; TanStack Query wants a value or a thrown error. */
export async function unwrap<T>(
  call: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, error, response } = await call;
  if (!response.ok) {
    throw new ApiError(response.status, isProblem(error) ? error : undefined);
  }
  return data as T;
}

function isProblem(value: unknown): value is Problem {
  return typeof value === "object" && value !== null && "status" in value && "title" in value;
}
