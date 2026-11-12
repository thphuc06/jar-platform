import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { fetchAuthSession, signInWithRedirect, signOut as amplifySignOut, type AuthSession } from "aws-amplify/auth";
import { Hub } from "aws-amplify/utils";
import { safeReturnPath } from "./config";
import { COGNITO, DEV_SUB } from "./env";
import { onSessionExpired } from "./session";
import { AuthContext, type AuthContextValue, type AuthStatus, type AuthUser } from "./useAuth";

export const RETURN_TO_KEY = "jars.auth.returnTo";

const CONFIG_ERROR =
  "Thiếu cấu hình đăng nhập. Hãy đặt VITE_COGNITO_USER_POOL_ID, VITE_COGNITO_CLIENT_ID và VITE_COGNITO_DOMAIN " +
  "(xem apps/web/.env.example), hoặc VITE_DEV_TOKEN_SUB để chạy ở chế độ dev-token.";

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<AuthStatus>(DEV_SUB ? "authenticated" : COGNITO ? "loading" : "anonymous");
  const [user, setUser] = useState<AuthUser | null>(DEV_SUB ? { sub: DEV_SUB } : null);
  const [error, setError] = useState<string | null>(DEV_SUB || COGNITO ? null : CONFIG_ERROR);
  const statusRef = useRef(status);
  useEffect(() => {
    statusRef.current = status;
  }, [status]);

  const markAnonymous = useCallback(() => {
    setUser(null);
    setStatus("anonymous");
    // the next person on this browser must not see the previous person's data
    queryClient.clear();
  }, [queryClient]);

  /** The server (or Cognito) refused our tokens: drop them locally; the route guard then shows /login. */
  const expire = useCallback(() => {
    if (statusRef.current === "authenticated") markAnonymous();
    void amplifySignOut().catch(() => undefined);
  }, [markAnonymous]);

  const applySession = useCallback(
    (session: AuthSession) => {
      if (!session.tokens?.accessToken) {
        if (statusRef.current === "authenticated") markAnonymous();
        else setStatus("anonymous");
        return;
      }
      const email = session.tokens.idToken?.payload.email;
      setUser({ sub: session.userSub ?? "", email: typeof email === "string" ? email : undefined });
      setError(null);
      setStatus("authenticated");
    },
    [markAnonymous],
  );

  const refresh = useCallback(
    () => fetchAuthSession().then(applySession, markAnonymous),
    [applySession, markAnonymous],
  );

  // initial session + Amplify events
  useEffect(() => {
    if (!COGNITO) return;
    void refresh();
    return Hub.listen("auth", ({ payload }) => {
      switch (payload.event) {
        case "signInWithRedirect":
        case "signedIn":
          void refresh();
          break;
        case "signInWithRedirect_failure":
          setError(payload.data.error?.message ?? "Đăng nhập không thành công. Vui lòng thử lại.");
          markAnonymous();
          break;
        case "tokenRefresh_failure":
          expire();
          break;
        case "signedOut":
          markAnonymous();
          break;
      }
    });
  }, [refresh, markAnonymous, expire]);

  // the API (HTTP 401) or the notification stream says the token is no good
  useEffect(
    () =>
      onSessionExpired(() => {
        if (import.meta.env.DEV && DEV_SUB) {
          // dev-token mode: forget the cached token, the next request fetches a new one
          void import("./devToken").then((m) => m.invalidateDevToken());
          return;
        }
        expire();
      }),
    [expire],
  );

  const signIn = useCallback(async (returnTo?: string) => {
    if (!COGNITO) {
      setError(CONFIG_ERROR);
      return;
    }
    setError(null);
    try {
      sessionStorage.setItem(RETURN_TO_KEY, safeReturnPath(returnTo));
    } catch {
      // storage blocked: the user lands on "/" after signing in
    }
    await signInWithRedirect();
  }, []);

  const signOut = useCallback(async () => {
    if (!COGNITO) return;
    markAnonymous();
    await amplifySignOut();
  }, [markAnonymous]);

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, devMode: Boolean(DEV_SUB), error, refresh, signIn, signOut }),
    [status, user, error, refresh, signIn, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
