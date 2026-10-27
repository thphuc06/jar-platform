import { Loader2 } from "lucide-react";
import { useAuth } from "react-oidc-context";
import { Link, Navigate } from "react-router-dom";
import { useRiskProfile } from "@/api/queries";
import { safeReturnTo } from "@/auth/oidc";

/**
 * Cognito redirects here with ?code=...&state=...; the AuthProvider exchanges the code (PKCE) for tokens. Then flow
 * F1: a user without a risk profile answers the questionnaire first, everyone else goes back where they started.
 */
const AuthCallback = () => {
  const auth = useAuth();
  // only once the code has been exchanged: before that there is no token and the call would be a 401
  const riskProfile = useRiskProfile(auth.isAuthenticated);

  if (auth.error) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-3 px-6 text-center">
        <p role="alert" className="text-sm text-destructive">Sign-in failed: {auth.error.message}</p>
        <Link to="/" className="text-sm text-primary underline">Back to the start page</Link>
      </div>
    );
  }
  if (auth.isAuthenticated && riskProfile.isSuccess && riskProfile.data === null) {
    return <Navigate to="/onboarding" replace />;
  }
  // also when the profile could not be read: the questionnaire is offered again from the Profile page
  if (auth.isAuthenticated && (riskProfile.isSuccess || riskProfile.isError)) {
    return <Navigate to={safeReturnTo(auth.user?.state)} replace />;
  }
  return (
    <div className="flex min-h-screen items-center justify-center">
      <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" aria-label="Signing in" />
    </div>
  );
};

export default AuthCallback;
