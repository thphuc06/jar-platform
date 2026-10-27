import { useRef } from "react";
import { ApiError } from "./client";

/**
 * One Idempotency-Key per user action (api-contract §4), with three rules:
 *  - the SAME body sent again after a failure (lost response, 5xx, the user pressing Save again) reuses the key, so
 *    the server replays the stored result instead of moving the money twice;
 *  - a DIFFERENT body gets a new key (reusing it would be 422 idempotency-key-reused);
 *  - after a success, call done(): the next save is a new action with a new key.
 */
export function useIdempotencyKey() {
  const attempt = useRef<{ key: string; body: string } | null>(null);
  return {
    keyFor(body: unknown): string {
      const json = JSON.stringify(body);
      if (attempt.current?.body !== json) {
        attempt.current = { key: crypto.randomUUID(), body: json };
      }
      return attempt.current.key;
    },
    done() {
      attempt.current = null;
    },
  };
}

/**
 * Retrying a money-moving POST is safe ONLY because the retry carries the same Idempotency-Key. Retried: a network
 * failure (the request may or may not have reached the server) and a 5xx (the server rolled the attempt back, and a
 * failed attempt is never stored under the key). Never retried: 4xx, the answer would not change.
 */
export function retryIdempotent(failures: number, error: Error): boolean {
  const transient = !(error instanceof ApiError) || error.status >= 500;
  return transient && failures < 2;
}

export const retryDelay = (attempt: number) => Math.min(500 * 2 ** attempt, 4000);
