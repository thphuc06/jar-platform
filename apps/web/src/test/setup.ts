import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";
import "./network";

// the API client asks for a token on every request; there is no Cognito in tests
vi.mock("@/auth/session", () => ({
  getAccessToken: async () => "test-access-token",
  notifySessionExpired: vi.fn(),
  onSessionExpired: () => () => undefined,
}));

// jsdom has no matchMedia (used by next-themes) and no ResizeObserver (used by Recharts' ResponsiveContainer)
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => {},
  }),
});

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= ResizeObserverStub;

afterEach(() => cleanup());
