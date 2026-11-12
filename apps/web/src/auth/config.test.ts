import { describe, expect, it } from "vitest";
import { buildAmplifyConfig, normalizeDomain, readCognitoEnv, safeReturnPath } from "./config";

describe("readCognitoEnv", () => {
  const full = {
    VITE_COGNITO_USER_POOL_ID: "ap-southeast-1_AbCdEf123",
    VITE_COGNITO_CLIENT_ID: "3n4b5c6d7e8f9g0h1i2j3k4l5m",
    VITE_COGNITO_DOMAIN: "jars-dev.auth.ap-southeast-1.amazoncognito.com",
  };

  it("returns the three settings", () => {
    expect(readCognitoEnv(full)).toEqual({
      userPoolId: "ap-southeast-1_AbCdEf123",
      clientId: "3n4b5c6d7e8f9g0h1i2j3k4l5m",
      domain: "jars-dev.auth.ap-southeast-1.amazoncognito.com",
    });
  });

  it("returns null when any of them is missing or blank", () => {
    expect(readCognitoEnv({})).toBeNull();
    expect(readCognitoEnv({ ...full, VITE_COGNITO_CLIENT_ID: "  " })).toBeNull();
    expect(readCognitoEnv({ ...full, VITE_COGNITO_DOMAIN: undefined })).toBeNull();
  });
});

describe("normalizeDomain", () => {
  it("strips the scheme and trailing slashes (Amplify wants a bare host)", () => {
    expect(normalizeDomain("https://jars-dev.auth.ap-southeast-1.amazoncognito.com/")).toBe(
      "jars-dev.auth.ap-southeast-1.amazoncognito.com",
    );
    expect(normalizeDomain(" HTTP://x.example.com ")).toBe("x.example.com");
    expect(normalizeDomain("x.example.com")).toBe("x.example.com");
  });
});

describe("buildAmplifyConfig", () => {
  const config = buildAmplifyConfig(
    { userPoolId: "ap-southeast-1_AbCdEf123", clientId: "client", domain: "d.auth.example.com" },
    "http://localhost:5173",
  );
  const cognito = config.Auth?.Cognito;

  it("is a public client with the authorization code flow (PKCE), no secret", () => {
    expect(cognito?.userPoolClientId).toBe("client");
    expect(cognito?.loginWith?.oauth?.responseType).toBe("code");
    expect(JSON.stringify(config)).not.toMatch(/secret/i);
  });

  it("uses the callback and logout URLs that infra registers on the app client", () => {
    expect(cognito?.loginWith?.oauth?.redirectSignIn).toEqual(["http://localhost:5173/auth/callback"]);
    expect(cognito?.loginWith?.oauth?.redirectSignOut).toEqual(["http://localhost:5173/"]);
    expect(cognito?.loginWith?.oauth?.scopes).toEqual(["openid", "email", "profile"]);
  });
});

describe("safeReturnPath", () => {
  it("keeps in-app paths", () => {
    expect(safeReturnPath("/jars")).toBe("/jars");
    expect(safeReturnPath("/inbox?type=EXPENSE")).toBe("/inbox?type=EXPENSE");
  });

  it("refuses anything that could leave the app or loop through the login pages", () => {
    expect(safeReturnPath("https://evil.example/")).toBe("/");
    expect(safeReturnPath("//evil.example/")).toBe("/");
    expect(safeReturnPath("/\\evil.example")).toBe("/");
    expect(safeReturnPath("/auth/callback?code=1")).toBe("/");
    expect(safeReturnPath("/login")).toBe("/");
    expect(safeReturnPath(null)).toBe("/");
    expect(safeReturnPath("")).toBe("/");
  });
});
