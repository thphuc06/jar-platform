import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { AuthContextProps } from "react-oidc-context";
import { Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderAt } from "@/test/render";
import RequireAuth from "./RequireAuth";

const auth = vi.hoisted(() => ({ current: {} as Partial<AuthContextProps> }));
vi.mock("react-oidc-context", () => ({ useAuth: () => auth.current }));

const guarded = (
  <Routes>
    <Route element={<RequireAuth />}>
      <Route path="/goals" element={<p>goals page</p>} />
    </Route>
  </Routes>
);

describe("RequireAuth", () => {
  beforeEach(() => {
    auth.current = { isLoading: false, isAuthenticated: false, signinRedirect: vi.fn() };
  });

  it("shows the protected page to a signed-in user", () => {
    auth.current = { isLoading: false, isAuthenticated: true };
    renderAt("/goals", guarded, "/*");

    expect(screen.getByText("goals page")).toBeInTheDocument();
  });

  it("asks a signed-out user to sign in and remembers the page they wanted", async () => {
    renderAt("/goals?tab=active", guarded, "/*");

    expect(screen.queryByText("goals page")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(auth.current.signinRedirect).toHaveBeenCalledWith({ state: "/goals?tab=active" });
  });

  it("never redirects on its own, so a failed sign-in cannot loop", () => {
    auth.current = { ...auth.current, error: Object.assign(new Error("access_denied"), { source: "signinCallback" }) as never };
    renderAt("/goals", guarded, "/*");

    expect(screen.getByRole("alert")).toHaveTextContent("access_denied");
    expect(auth.current.signinRedirect).not.toHaveBeenCalled();
  });
});
