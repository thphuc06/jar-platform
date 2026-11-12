import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "./problem";

const MAX_RETRIES = 2;

/**
 * Network failures and 5xx are retried twice. A 4xx will answer the same again (401 starts a new login, 404 and 422 are
 * the answer), except 408 and 429.
 */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (failureCount >= MAX_RETRIES) return false;
  if (error instanceof ApiError) return error.status >= 500 || error.status === 408 || error.status === 429;
  return true;
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: shouldRetry, staleTime: 30_000 },
      mutations: { retry: false },
    },
  });
}
