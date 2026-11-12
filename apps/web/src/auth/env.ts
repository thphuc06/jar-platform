import { readCognitoEnv } from "./config";

/**
 * Dev-token subject. In a production build `import.meta.env.DEV` is replaced by `false`, so this is always
 * undefined there and every branch guarded by it is dropped.
 */
export const DEV_SUB: string | undefined = import.meta.env.DEV
  ? import.meta.env.VITE_DEV_TOKEN_SUB || undefined
  : undefined;

/** Cognito settings from the build environment; null in dev-token mode or when they are missing. */
export const COGNITO = DEV_SUB ? null : readCognitoEnv(import.meta.env);
