import { Amplify } from "aws-amplify";
import { buildAmplifyConfig } from "./config";
import { COGNITO } from "./env";

/**
 * Call once before the first render. With the OAuth listener that `signInWithRedirect` enables, configuring Amplify
 * on `/auth/callback?code=...` completes the code exchange (PKCE verifier from storage) and stores the tokens.
 * Does nothing in dev-token mode or when the Cognito variables are missing (AuthProvider reports that).
 */
export function configureAuth(): void {
  if (COGNITO) Amplify.configure(buildAmplifyConfig(COGNITO, window.location.origin));
}
