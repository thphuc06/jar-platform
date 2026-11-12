import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import ProtectedRoute from "./ProtectedRoute";
import { AuthContext, type AuthContextValue, type AuthStatus } from "./useAuth";

function auth(status: AuthStatus): AuthContextValue {
  return {
    status,
    user: status === "authenticated" ? { sub: "user-1" } : null,
    devMode: false,
    error: null,
    refresh: vi.fn(),
    signIn: vi.fn(),
    signOut: vi.fn(),
  };
}

const LoginProbe = () => {
  const location = useLocation();
  return <p>login, came from {(location.state as { from?: string } | null)?.from}</p>;
};

function renderAt(path: string, status: AuthStatus) {
  render(
    <AuthContext.Provider value={auth(status)}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<LoginProbe />} />
          <Route element={<ProtectedRoute />}>
            <Route path="/jars" element={<p>jars screen</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe("ProtectedRoute", () => {
  it("shows the page to a signed-in user", () => {
    renderAt("/jars", "authenticated");
    expect(screen.getByText("jars screen")).toBeInTheDocument();
  });

  it("sends an anonymous user to the login screen and remembers where they wanted to go", () => {
    renderAt("/jars?tab=1", "anonymous");
    expect(screen.getByText("login, came from /jars?tab=1")).toBeInTheDocument();
  });

  it("waits while the session is being read (no flash of the login screen)", () => {
    renderAt("/jars", "loading");
    expect(screen.queryByText("jars screen")).not.toBeInTheDocument();
    expect(screen.queryByText(/login/)).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toBeInTheDocument();
  });
});
