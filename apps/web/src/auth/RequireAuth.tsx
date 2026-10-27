import { Loader2, LogIn, ShieldAlert } from "lucide-react";
import { useAuth } from "react-oidc-context";
import { Outlet, useLocation } from "react-router-dom";
import { Button } from "@/components/ui/button";

/**
 * Guards the app routes. Signed out: a sign-in screen (no automatic redirect, so a failed or cancelled login cannot
 * loop). Sign-in and sign-up both happen on Cognito managed login; the app never sees a password.
 */
const RequireAuth = () => {
  const auth = useAuth();
  const location = useLocation();

  if (auth.isLoading || auth.activeNavigator) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" aria-label="Loading" />
      </div>
    );
  }

  if (auth.isAuthenticated) {
    return <Outlet />;
  }

  const signIn = () => auth.signinRedirect({ state: location.pathname + location.search });

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-background px-6">
      <div className="w-full max-w-sm space-y-6 text-center animate-slide-up">
        <div className="mx-auto flex h-20 w-20 items-center justify-center rounded-2xl gradient-premium">
          <LogIn className="h-10 w-10 text-primary-foreground" />
        </div>
        <div>
          <h1 className="text-3xl font-display font-bold">Jars</h1>
          <p className="mt-2 text-muted-foreground">Personal finance with the 6-jar method.</p>
        </div>
        {auth.error && (
          <p role="alert" className="flex items-center justify-center gap-1.5 text-sm text-destructive">
            <ShieldAlert className="h-4 w-4" /> Sign-in failed: {auth.error.message}
          </p>
        )}
        <Button onClick={signIn} className="w-full gap-2" size="lg">
          Sign in or create an account
        </Button>
      </div>
    </div>
  );
};

export default RequireAuth;
