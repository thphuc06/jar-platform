import { userManager } from "@/auth/oidc";
import { createApiClient } from "./client";

export { ApiError, unwrap } from "./client";
export type { components, paths } from "./schema";

/** The typed client of jars-api, generated from contracts/openapi/jars-api.yaml (npm run gen:api). */
export const api = createApiClient(() => userManager.getUser());
