import { describe, expect, it } from "vitest";
import { cognitoLogoutParams, oidcSettings, safeReturnTo, SCOPES } from "./oidc";

const config = {
  authority: "https://cognito-idp.ap-southeast-1.amazonaws.com/ap-southeast-1_TEST",
  clientId: "client",
  origin: "http://localhost:5173",
};

describe("oidc settings for Cognito", () => {
  it("uses the authorization code flow with the URLs registered on the app client", () => {
    const settings = oidcSettings(config);

    expect(settings).toMatchObject({
      response_type: "code",
      redirect_uri: "http://localhost:5173/auth/callback",
      client_id: "client",
      revokeTokensOnSignout: true,
    });
    expect(settings).not.toHaveProperty("client_secret");
    expect(SCOPES.split(" ")).toEqual(["openid", "email", "profile", "jars-api/read", "jars-api/write"]);
    expect(SCOPES).not.toContain("offline_access"); // Cognito answers invalid_scope
  });

  it("logs out with Cognito's own parameters (it ignores post_logout_redirect_uri)", () => {
    expect(cognitoLogoutParams(config)).toEqual({ client_id: "client", logout_uri: "http://localhost:5173/" });
  });
});

describe("safeReturnTo", () => {
  it.each(["/", "/goals", "/inbox?jarId=1#top"])("keeps the same-origin path %s", (path) => {
    expect(safeReturnTo(path)).toBe(path);
  });

  it.each([
    "//evil.example/phish", // protocol-relative: another host
    "/\\evil.example", // browsers read a backslash as a slash
    "https://evil.example",
    "javascript:alert(1)",
    "",
    undefined,
    42,
  ])("falls back to / for %s", (value) => {
    expect(safeReturnTo(value)).toBe("/");
  });
});
