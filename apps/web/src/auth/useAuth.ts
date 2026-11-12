import { createContext, useContext } from "react";

export type AuthStatus = "loading" | "authenticated" | "anonymous";

export interface AuthUser {
  /** Cognito `sub` (or the dev-token subject in dev-token mode) */
  sub: string;
  /** From the ID token; the API's access token has no email claim (flows.md F1) */
  email?: string;
}

export interface AuthContextValue {
  status: AuthStatus;
  user: AuthUser | null;
  /** True when the dev server runs in dev-token mode (VITE_DEV_TOKEN_SUB): no Cognito, no login screen */
  devMode: boolean;
  /** Set when the sign-in redirect came back with an error, or when the Cognito variables are missing */
  error: string | null;
  /** Re-reads the Cognito session; the callback page polls it while the code exchange is running */
  refresh: () => Promise<void>;
  /** Starts Authorization Code + PKCE against the managed login page. `returnTo` is where to land afterwards. */
  signIn: (returnTo?: string) => Promise<void>;
  signOut: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside <AuthProvider>");
  return value;
}
