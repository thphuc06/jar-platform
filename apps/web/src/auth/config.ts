import type { ResourcesConfig } from "aws-amplify";

/** What the app needs from Cognito. All three come from the build environment; none of them is a secret. */
export interface CognitoEnv {
  userPoolId: string;
  clientId: string;
  /** Managed login domain without scheme: <prefix>.auth.<region>.amazoncognito.com */
  domain: string;
}

type EnvSource = Pick<ImportMetaEnv, "VITE_COGNITO_USER_POOL_ID" | "VITE_COGNITO_CLIENT_ID" | "VITE_COGNITO_DOMAIN">;

/** `https://x.auth.region.amazoncognito.com/` -> `x.auth.region.amazoncognito.com` (Amplify wants no scheme). */
export function normalizeDomain(raw: string): string {
  return raw.trim().replace(/^https?:\/\//i, "").replace(/\/+$/, "");
}

/** Returns null when any of the three variables is missing, so the UI can say what to configure. */
export function readCognitoEnv(env: EnvSource): CognitoEnv | null {
  const userPoolId = env.VITE_COGNITO_USER_POOL_ID?.trim();
  const clientId = env.VITE_COGNITO_CLIENT_ID?.trim();
  const domain = env.VITE_COGNITO_DOMAIN ? normalizeDomain(env.VITE_COGNITO_DOMAIN) : "";
  if (!userPoolId || !clientId || !domain) return null;
  return { userPoolId, clientId, domain };
}

/**
 * Authorization Code + PKCE against the public SPA app client (no secret). The redirect URLs are built from the
 * page origin, so the same build works on localhost:5173 and on the CloudFront domain; both are registered on
 * the app client (infra AppStack): callback `<origin>/auth/callback`, logout `<origin>/`.
 */
export function buildAmplifyConfig(env: CognitoEnv, origin: string): ResourcesConfig {
  return {
    Auth: {
      Cognito: {
        userPoolId: env.userPoolId,
        userPoolClientId: env.clientId,
        loginWith: {
          oauth: {
            domain: env.domain,
            scopes: ["openid", "email", "profile"],
            redirectSignIn: [`${origin}/auth/callback`],
            redirectSignOut: [`${origin}/`],
            responseType: "code",
          },
        },
      },
    },
  };
}

/** Only same-app paths may be a post-login destination (no open redirect through `?from=`). */
export function safeReturnPath(path: string | null | undefined): string {
  if (!path || !path.startsWith("/") || path.startsWith("//") || path.startsWith("/\\")) return "/";
  if (path.startsWith("/auth/") || path === "/login") return "/";
  return path;
}
