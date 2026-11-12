import { fetchAuthSession } from "aws-amplify/auth";

/**
 * The bearer token for jars-api. It is the ACCESS token: the API validates `token_use=access` and the client id,
 * an ID token is rejected with 401 (docs/02-architecture/flows.md F1).
 *
 * Amplify refreshes an expired access token by itself inside `fetchAuthSession()`.
 */
export async function getAccessToken(): Promise<string | undefined> {
  // The condition is spelled out here (not behind a helper) so the bundler drops this branch, and the
  // dev-token module with it, from a production build.
  if (import.meta.env.DEV && import.meta.env.VITE_DEV_TOKEN_SUB) {
    const { getDevAccessToken } = await import("./devToken");
    return getDevAccessToken(import.meta.env.VITE_DEV_TOKEN_SUB);
  }
  const session = await fetchAuthSession();
  return session.tokens?.accessToken?.toString();
}

// ---- "the server says our token is no good" ---------------------------------------------------------------

type Listener = () => void;
const listeners = new Set<Listener>();

/** Called by the API client (HTTP 401) and the notification stream. AuthProvider decides what to do. */
export function notifySessionExpired(): void {
  for (const listener of [...listeners]) listener();
}

export function onSessionExpired(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
