/**
 * Test doubles for the network, installed by setup.ts before any test file imports the API client (which captures
 * `fetch` and `Request` when it is created).
 *
 * - `fetchMock` replaces `fetch`; tests give it behaviour with `serve()`.
 * - `Request` accepts the app's relative URLs ("/api/v1/jars"): a browser resolves them, Node's Request does not.
 */
import { vi } from "vitest";

export const fetchMock = vi.fn<(request: Request) => Promise<Response>>();

const NativeRequest = globalThis.Request;
globalThis.Request = class extends NativeRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    super(typeof input === "string" && input.startsWith("/") ? `http://localhost${input}` : input, init);
  }
} as typeof Request;
globalThis.fetch = fetchMock as unknown as typeof fetch;

export const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });

export const problem = (status: number, title: string, detail?: string) =>
  new Response(JSON.stringify({ title, status, detail }), { status, headers: { "content-type": "application/problem+json" } });

export interface Call {
  method: string;
  pathname: string;
  searchParams: URLSearchParams;
  request: Request;
}

/** Routes every request to `handler`; a handler that returns undefined makes the test fail loudly. */
export function serve(handler: (call: Call) => Response | undefined | Promise<Response | undefined>): void {
  fetchMock.mockImplementation(async (request) => {
    const url = new URL(request.url);
    const call = { method: request.method, pathname: url.pathname, searchParams: url.searchParams, request };
    const response = await handler(call);
    if (!response) throw new Error(`Test server has no answer for ${request.method} ${url.pathname}${url.search}`);
    return response;
  });
}

/** The requests made so far, as `{method, pathname, searchParams}`. */
export function calls(): Call[] {
  return fetchMock.mock.calls.map(([request]) => {
    const url = new URL(request.url);
    return { method: request.method, pathname: url.pathname, searchParams: url.searchParams, request };
  });
}
