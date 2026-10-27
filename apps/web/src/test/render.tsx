import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { vi } from "vitest";
import { Toaster } from "@/components/ui/toaster";

/** Shows the current location, so a test can assert where a component navigated to. */
const Where = () => {
  const location = useLocation();
  return <p data-testid="location">{location.pathname + location.search}</p>;
};

/** Renders `element` at `path` with a fresh QueryClient (no retries) and a router that records navigation. */
export function renderAt(path: string, element: ReactElement, routePath = path.split("?")[0]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={routePath} element={element} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
      <Toaster />
    </QueryClientProvider>,
  );
}

export function jsonResponse(status: number, body: unknown) {
  const contentType = status >= 400 ? "application/problem+json" : "application/json";
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": contentType } });
}

type Handler = (url: URL, request: Request) => Response | Promise<Response>;

/**
 * A fake jars-api: routes "GET /api/v1/transactions" style keys to handlers and records every request, so a test can
 * check exactly what the UI sent (method, query string, body).
 */
export function fakeApi(routes: Record<string, Handler>) {
  const requests: Request[] = [];
  const fetch = vi.fn(async (request: Request) => {
    requests.push(request.clone());
    const url = new URL(request.url);
    const handler = routes[`${request.method} ${url.pathname}`];
    if (!handler) {
      return jsonResponse(404, { type: "https://jars.local/problems/not-found", title: "Not Found", status: 404 });
    }
    return handler(url, request);
  });
  vi.stubGlobal("fetch", fetch);
  const sent = (method: string, path: string) =>
    requests.filter((r) => r.method === method && new URL(r.url).pathname === path);
  return { requests, sent };
}
