import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";
import { calls, fetchMock, json, problem, serve } from "@/test/network";
import { categories, jars } from "@/test/fixtures";
import Transfer from "./Transfer";

const receipt = {
  transaction: { id: "22222222-2222-4222-8222-222222222222", type: "INCOME", amount: { amount: "85000.00", currency: "VND" } },
  jarBalances: [],
};

function postKeys(): string[] {
  return calls()
    .filter((c) => c.method === "POST")
    .map((c) => c.request.headers.get("idempotency-key") ?? "");
}

function renderIncomeForm() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/transfer?mode=income"]}>
        <Routes>
          <Route path="/transfer" element={<Transfer />} />
          <Route path="/" element={<p>Trang chủ</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("Transfer: income form and Idempotency-Key", () => {
  beforeEach(() => {
  fetchMock.mockReset(); // not returned: a function returned from beforeEach would run as a cleanup hook
});

  /** POST /transactions answers per `postResponses` (one per call); the reads answer normally. */
  function serveWith(postResponses: Array<() => Response>) {
    serve(({ method, pathname }) => {
      if (method === "GET" && pathname === "/api/v1/jars") return json(jars);
      if (method === "GET" && pathname === "/api/v1/categories") return json(categories);
      if (method === "POST" && pathname === "/api/v1/transactions") {
        const next = postResponses.shift();
        if (!next) throw new Error("unexpected extra POST");
        return next();
      }
      return undefined;
    });
  }

  const unavailable = () => problem(503, "Dịch vụ tạm thời không khả dụng");

  it("retries the same intent with the SAME key, sends the amount as a string and the bearer token", async () => {
    serveWith([unavailable, () => json(receipt, 201)]);
    renderIncomeForm();

    fireEvent.change(await screen.findByLabelText("Số tiền (VND)"), { target: { value: "85000" } });
    const submit = screen.getByRole("button", { name: /Ghi khoản thu/ });

    fireEvent.click(submit);
    await waitFor(() => expect(postKeys()).toHaveLength(1));
    await waitFor(() => expect(submit).toBeEnabled()); // failed: the form stays, the user can try again

    fireEvent.click(submit);
    await waitFor(() => expect(screen.getByText("Trang chủ")).toBeInTheDocument()); // success: navigated home

    const keys = postKeys();
    expect(keys).toHaveLength(2);
    expect(keys[0]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(keys[1]).toBe(keys[0]);

    const post = calls().find((c) => c.method === "POST")!.request;
    expect(post.headers.get("authorization")).toBe("Bearer test-access-token");
    expect(await post.clone().json()).toEqual({ type: "INCOME", amount: "85000" });
  });

  it("uses a NEW key when the user changed the amount after a failure", async () => {
    serveWith([unavailable, () => json(receipt, 201)]);
    renderIncomeForm();

    const amount = await screen.findByLabelText("Số tiền (VND)");
    fireEvent.change(amount, { target: { value: "85000" } });
    const submit = screen.getByRole("button", { name: /Ghi khoản thu/ });
    fireEvent.click(submit);
    await waitFor(() => expect(postKeys()).toHaveLength(1));
    await waitFor(() => expect(submit).toBeEnabled());

    fireEvent.change(amount, { target: { value: "90000" } });
    fireEvent.click(submit);
    await waitFor(() => expect(screen.getByText("Trang chủ")).toBeInTheDocument());

    const keys = postKeys();
    expect(keys).toHaveLength(2);
    expect(keys[1]).not.toBe(keys[0]);
  });

  it("does not send anything until there is a valid amount", async () => {
    serveWith([]);
    renderIncomeForm();

    expect(await screen.findByRole("button", { name: /Ghi khoản thu/ })).toBeDisabled();
    expect(postKeys()).toHaveLength(0);
  });
});
