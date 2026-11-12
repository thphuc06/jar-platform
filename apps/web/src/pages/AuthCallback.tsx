import { useEffect, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { RETURN_TO_KEY } from "@/auth/AuthProvider";
import { safeReturnPath } from "@/auth/config";
import { useAuth } from "@/auth/useAuth";
import PageSpinner from "@/components/PageSpinner";

const POLL_MS = 500;
const GIVE_UP_MS = 20_000;

/**
 * Redirect target of the Cognito managed login (`/auth/callback?code=...&state=...`). Amplify exchanges the code
 * (PKCE) as soon as it is configured; this page waits for the session to appear and then continues to where the user
 * wanted to go.
 */
const AuthCallback = () => {
  const { status, error, refresh } = useAuth();
  const navigate = useNavigate();
  // read once: Amplify removes the query string after it has handled it
  const [cameFromCognito] = useState(() => new URLSearchParams(window.location.search).has("code"));
  const [timedOut, setTimedOut] = useState(false);

  useEffect(() => {
    if (status !== "authenticated") return;
    let target = "/";
    try {
      target = safeReturnPath(sessionStorage.getItem(RETURN_TO_KEY));
      sessionStorage.removeItem(RETURN_TO_KEY);
    } catch {
      // storage blocked: go to the home screen
    }
    navigate(target, { replace: true });
  }, [status, navigate]);

  // the code exchange may still be running when the provider first reads the session: look again until it is done
  useEffect(() => {
    if (!cameFromCognito || status === "authenticated" || error) return;
    const poll = setInterval(() => void refresh(), POLL_MS);
    const giveUp = setTimeout(() => setTimedOut(true), GIVE_UP_MS);
    return () => {
      clearInterval(poll);
      clearTimeout(giveUp);
    };
  }, [cameFromCognito, status, error, refresh]);

  if (error || timedOut) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background p-6">
        <Card className="w-full max-w-sm">
          <CardContent className="space-y-4 p-6 text-center">
            <h1 className="text-lg font-display font-bold">Đăng nhập không thành công</h1>
            <p role="alert" className="text-sm text-muted-foreground">
              {error ?? "Không hoàn tất được đăng nhập. Vui lòng thử lại."}
            </p>
            <Button asChild className="w-full">
              <Link to="/login">Quay lại trang đăng nhập</Link>
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  // opened by hand, without a code from Cognito
  if (!cameFromCognito && status === "anonymous") return <Navigate to="/login" replace />;

  return <PageSpinner label="Đang hoàn tất đăng nhập..." />;
};

export default AuthCallback;
