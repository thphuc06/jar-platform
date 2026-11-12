import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";
import { calls, fetchMock, json, problem, serve } from "@/test/network";
import Advisor from "./Advisor";

const session = { id: "s1", title: "Hũ thiết yếu", createdAt: "2026-11-13T01:00:00Z", updatedAt: "2026-11-13T01:00:00Z" };

/** An SSE response the way jars-api writes it. */
const sse = (...frames: [string, unknown][]) =>
  new Response(frames.map(([event, data]) => `event:${event}\ndata:${JSON.stringify(data)}\n\n`).join(""), {
    status: 200,
    headers: { "content-type": "text/event-stream" },
  });

function renderAdvisor() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Advisor />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const ask = (text: string) => {
  fireEvent.change(screen.getByLabelText("Câu hỏi cho trợ lý"), { target: { value: text } });
  fireEvent.click(screen.getByRole("button", { name: "Gửi" }));
};

beforeEach(() => {
  fetchMock.mockReset();
});

describe("Advisor", () => {
  it("creates a conversation, streams the answer and shows the disclaimer", async () => {
    let stored: unknown[] = [];
    serve(({ method, pathname }) => {
      if (method === "GET" && pathname === "/api/v1/chat/sessions") return json(stored.length ? [session] : []);
      if (method === "POST" && pathname === "/api/v1/chat/sessions") return json(session, 201);
      if (method === "POST" && pathname === "/api/v1/chat/sessions/s1/messages") {
        stored = [
          { id: "m1", role: "USER", content: "Hũ NEC còn bao nhiêu?", createdAt: "2026-11-13T01:00:01Z" },
          { id: "m2", role: "ASSISTANT", content: "Hũ Thiết yếu của bạn đang có 2.500.000 ₫.", createdAt: "2026-11-13T01:00:02Z" },
        ];
        return sse(
          ["tool", { name: "getJars", status: "started" }],
          ["tool", { name: "getJars", status: "done" }],
          ["token", { text: "Hũ Thiết yếu của bạn " }],
          ["token", { text: "đang có 2.500.000 ₫." }],
          ["done", { messageId: "m2", usage: { inputTokens: 120, outputTokens: 30 }, disclaimer: "Thông tin mang tính tham khảo." }],
        );
      }
      if (method === "GET" && pathname === "/api/v1/chat/sessions/s1/messages") return json(stored);
      return undefined;
    });
    renderAdvisor();
    await screen.findByText(/Bắt đầu bằng một câu hỏi/);

    ask("Hũ NEC còn bao nhiêu?");

    // the streamed bubble is swapped for the stored message once the turn is done
    expect(await screen.findByText("Thông tin mang tính tham khảo.")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("Hũ Thiết yếu của bạn đang có 2.500.000 ₫.")).toBeInTheDocument());
    const send = calls().find((c) => c.method === "POST" && c.pathname.endsWith("/messages"))!;
    expect(send.request.headers.get("Authorization")).toBe("Bearer test-access-token");
    expect(JSON.parse(await send.request.clone().text())).toEqual({ content: "Hũ NEC còn bao nhiêu?" });
  });

  it("shows what the guardrail put in place of a masked answer", async () => {
    serve(({ method, pathname }) => {
      if (method === "GET" && pathname === "/api/v1/chat/sessions") return json([session]);
      if (method === "GET" && pathname === "/api/v1/chat/sessions/s1/messages") return json([]);
      if (method === "POST")
        return sse(
          ["token", { text: "Hãy mua cổ phiếu XYZ" }],
          ["replace", { text: "Tôi không đưa ra khuyến nghị mua bán chứng khoán cụ thể." }],
          ["done", { messageId: "m2", usage: { inputTokens: 1, outputTokens: 1 }, disclaimer: "x" }],
        );
      return undefined;
    });
    renderAdvisor();
    await screen.findByText(/Bắt đầu bằng một câu hỏi/);

    ask("Nên mua cổ phiếu nào?");

    // the masked text replaces what was streamed while the turn is still on screen
    await waitFor(() => expect(calls().some((c) => c.method === "POST")).toBe(true));
    await waitFor(() => expect(screen.queryByText("Hãy mua cổ phiếu XYZ")).not.toBeInTheDocument());
  });

  it("explains a refused message (rate limit) without breaking the page", async () => {
    serve(({ method, pathname }) => {
      if (method === "GET" && pathname === "/api/v1/chat/sessions") return json([session]);
      if (method === "GET" && pathname === "/api/v1/chat/sessions/s1/messages") return json([]);
      if (method === "POST")
        return problem(429, "Đã hết lượt hỏi trợ lý", "Bạn đã dùng hết lượt hỏi trợ lý trong giờ này. Hãy thử lại sau.");
      return undefined;
    });
    renderAdvisor();
    await screen.findByText(/Bắt đầu bằng một câu hỏi/);

    ask("Một câu nữa");

    expect(await screen.findByText(/dùng hết lượt hỏi trợ lý trong giờ này/)).toBeInTheDocument();
    expect(screen.getByLabelText("Câu hỏi cho trợ lý")).not.toBeDisabled();
  });

  it("does not send an empty question", async () => {
    serve(({ method, pathname }) => (method === "GET" && pathname === "/api/v1/chat/sessions" ? json([]) : undefined));
    renderAdvisor();
    await screen.findByText(/Bắt đầu bằng một câu hỏi/);

    expect(screen.getByRole("button", { name: "Gửi" })).toBeDisabled();
  });
});
