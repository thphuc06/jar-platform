import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
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
