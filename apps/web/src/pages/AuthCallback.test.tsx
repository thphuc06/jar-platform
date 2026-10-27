import { act, screen } from "@testing-library/react";
import type { AuthContextProps } from "react-oidc-context";
import { afterEach, describe, expect, it, vi } from "vitest";
import { jsonResponse, renderAt } from "@/test/render";
import AuthCallback from "./AuthCallback";

const auth = vi.hoisted(() => ({ current: {} as Partial<AuthContextProps> }));
vi.mock("react-oidc-context", () => ({ useAuth: () => auth.current }));

afterEach(() => vi.unstubAllGlobals());

const signedIn = (state: unknown) =>
  ({ isLoading: false, isAuthenticated: true, user: { state } }) as unknown as Partial<AuthContextProps>;

describe("AuthCallback (flow F1)", () => {
  it("sends a user without a risk profile to the questionnaire first", async () => {
    auth.current = signedIn("/goals");
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        jsonResponse(404, {
          type: "https://jars.local/problems/risk-profile-not-found",
          title: "Not Found",
          status: 404,
        }),
      ),
    );
    renderAt("/auth/callback?code=c&state=s", <AuthCallback />);

    expect(await screen.findByTestId("location")).toHaveTextContent("/onboarding");
  });

  it("returns everyone else to the page they started from, but never to another site", async () => {
    auth.current = signedIn("//evil.example/phish");
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(200, { riskLevel: "BALANCED" })));
    renderAt("/auth/callback?code=c&state=s", <AuthCallback />);

    expect(await screen.findByTestId("location")).toHaveTextContent(/^\/$/);
  });

  it("does not call the API before the code has been exchanged (there is no token yet)", async () => {
    auth.current = { isLoading: true, isAuthenticated: false };
    const fetch = vi.fn(async () => jsonResponse(200, {}));
    vi.stubGlobal("fetch", fetch);
    renderAt("/auth/callback?code=c&state=s", <AuthCallback />);

    // a query starts in an effect and the token middleware awaits before fetch() runs: checking right after
    // render would pass even if the call were made (found by mutating the code), so let that async work happen first
    await act(() => new Promise((resolve) => setTimeout(resolve, 100)));

    expect(screen.getByLabelText("Signing in")).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });
});
