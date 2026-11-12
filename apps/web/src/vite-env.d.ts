/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_COGNITO_USER_POOL_ID?: string;
  readonly VITE_COGNITO_CLIENT_ID?: string;
  readonly VITE_COGNITO_DOMAIN?: string;
  /** Dev server only: subject for POST /dev/token. Ignored (and tree-shaken) in production builds. */
  readonly VITE_DEV_TOKEN_SUB?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
