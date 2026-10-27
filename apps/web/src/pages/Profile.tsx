import { format } from "date-fns";
import { ChevronRight, LogOut, Shield, User } from "lucide-react";
import { useAuth } from "react-oidc-context";
import { useNavigate } from "react-router-dom";
import { useMe, useRiskProfile } from "@/api/queries";
import { cognito, cognitoLogoutParams } from "@/auth/oidc";
import ThemeToggle from "@/components/ThemeToggle";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

const LEVEL_STYLE: Record<string, string> = {
  CONSERVATIVE: "bg-info/10 text-info",
  BALANCED: "bg-primary/10 text-primary",
  GROWTH: "bg-accent/10 text-accent",
};

const Profile = () => {
  const auth = useAuth();
  const navigate = useNavigate();
  const me = useMe();
  const risk = useRiskProfile();

  const signOut = () => auth.signoutRedirect({ extraQueryParams: cognitoLogoutParams(cognito) });

  return (
    <div className="space-y-6 animate-slide-up">
      <h1 className="text-2xl font-display font-bold">Profile</h1>

      <Card>
        <CardContent className="flex items-center gap-4 p-5">
          <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl gradient-premium">
            <User className="h-7 w-7 text-primary-foreground" />
          </div>
          {me.isLoading ? (
            <div className="flex-1 space-y-2">
              <Skeleton className="h-4 w-1/2" />
              <Skeleton className="h-3 w-2/3" />
            </div>
          ) : me.data ? (
            <div className="min-w-0">
              <p className="truncate font-semibold">{me.data.displayName ?? me.data.email}</p>
              <p className="truncate text-sm text-muted-foreground">{me.data.email}</p>
              <p className="text-xs text-muted-foreground">
                Member since {format(new Date(me.data.createdAt), "dd/MM/yyyy")}
              </p>
            </div>
          ) : (
            <p role="alert" className="text-sm text-destructive">Could not load your profile: {me.error?.message}</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-3 p-5">
          <div className="flex items-center gap-2">
            <Shield className="h-4 w-4 text-primary" />
            <h2 className="font-display font-semibold">Risk profile</h2>
          </div>
          {risk.isLoading ? (
            <Skeleton className="h-10 w-full" />
          ) : risk.data ? (
            <div className="flex items-center justify-between">
              <div>
                <Badge className={LEVEL_STYLE[risk.data.riskLevel]} variant="secondary">
                  {risk.data.riskLevel}
                </Badge>
                <p className="mt-1 text-xs text-muted-foreground">
                  Score {risk.data.score} / {risk.data.maxScore}
                  {risk.data.capped && " · capped by your horizon or liquidity needs"} · assessed{" "}
                  {format(new Date(risk.data.assessedAt), "dd/MM/yyyy")}
                </p>
              </div>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">You have not answered the questionnaire yet.</p>
          )}
          <Button variant="outline" className="w-full justify-between" onClick={() => navigate("/onboarding")}>
            {risk.data ? "Answer the questionnaire again" : "Answer the questionnaire"}
            <ChevronRight className="h-4 w-4" />
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="flex items-center justify-between p-5">
          <span className="text-sm font-medium">Theme</span>
          <ThemeToggle />
        </CardContent>
      </Card>

      <Button variant="outline" className="w-full gap-2 text-destructive hover:text-destructive" onClick={signOut}>
        <LogOut className="h-4 w-4" /> Sign out
      </Button>
    </div>
  );
};

export default Profile;
