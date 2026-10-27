import { UserManager, WebStorageStateStore, type UserManagerSettings } from "oidc-client-ts";

/** Non-secret values of the Cognito user pool (a public SPA client has no secret). See .env.example. */
export interface CognitoConfig {
  /** Issuer, e.g. https://cognito-idp.<region>.amazonaws.com/<userPoolId>; discovery is read from it. */
  authority: string;
  clientId: string;
  /** App origin, e.g. http://localhost:5173: the callback and logout URLs registered on the app client. */
  origin: string;
}

export const SCOPES = "openid email profile jars-api/read jars-api/write";

export function oidcSettings(config: CognitoConfig): UserManagerSettings {
  return {
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: `${config.origin}/auth/callback`,
    // Authorization Code + PKCE (S256), the only flow the app client allows. No offline_access: Cognito rejects
    // it (invalid_scope) and returns a refresh token for the code grant anyway.
    response_type: "code",
    scope: SCOPES,
    // tokens live in sessionStorage: gone when the tab closes, not shared between tabs. Readable by any script on
    // the page, so XSS is the risk to design against (CSP in Phase 3); docs/learning/P1.11a-web-auth-pkce.md §4
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    // renew with the refresh token shortly before the 60-minute access token expires
    automaticSilentRenew: true,
    // logout also revokes the refresh token at Cognito (the app client has token revocation enabled)
    revokeTokensOnSignout: true,
    revokeTokenTypes: ["refresh_token"],
  };
}

/**
 * Cognito's /logout ignores the standard OIDC post_logout_redirect_uri (it shows the login page instead) and wants
 * its own parameters: client_id + logout_uri, which must be a registered sign-out URL.
 */
export function cognitoLogoutParams(config: CognitoConfig): Record<string, string> {
  return { client_id: config.clientId, logout_uri: `${config.origin}/` };
}

export function configFromEnv(): CognitoConfig {
  const authority = import.meta.env.VITE_COGNITO_AUTHORITY;
  const clientId = import.meta.env.VITE_COGNITO_CLIENT_ID;
  if (!authority || !clientId) {
    throw new Error("VITE_COGNITO_AUTHORITY and VITE_COGNITO_CLIENT_ID must be set (see apps/web/.env.example)");
  }
  return { authority, clientId, origin: window.location.origin };
}

export const cognito = configFromEnv();

/** One instance for the whole app: the React provider and the API client read the same user. */
export const userManager = new UserManager(oidcSettings(cognito));

/**
 * Where to go after sign-in. Only a path on this origin is accepted: "//evil.example" and "/\evil.example" are
 * protocol-relative URLs to another site (open redirect), so they fall back to "/".
 */
export function safeReturnTo(value: unknown): string {
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//") || value.includes("\\")) {
    return "/";
  }
  return value;
}
