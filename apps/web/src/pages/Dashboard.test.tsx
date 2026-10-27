import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { currentBusinessMonth, shiftMonth } from "@/lib/format";
import { FOOD, NEC, PLAY, POOL, tx } from "@/test/fixtures";
import { fakeApi, jsonResponse, renderAt } from "@/test/render";
import Dashboard from "./Dashboard";

afterEach(() => vi.unstubAllGlobals());

const dashboard = (month: string) => ({
  month,
  currency: "VND",
  balances: { total: "16800000.00", unallocated: "13000000.00", allocated: "3800000.00" },
  income: "10000000.00",
  expense: "630000.00",
  net: "9370000.00",
  byCategory: [
    { categoryId: FOOD, name: "Ăn uống", amount: "300000.00" },
    { categoryId: null, name: null, amount: "50000.00" },
  ],
  byJar: [
    { jarId: NEC.id, code: "NEC", name: "Necessities", balance: "2130000.00", spent: "300000.00" },
    { jarId: PLAY.id, code: "PLAY", name: "Play", balance: "1670000.00", spent: "330000.00" },
  ],
  trend: [{ month, income: "10000000.00", expense: "630000.00" }],
});

function api() {
  return fakeApi({
    "GET /api/v1/dashboard": (url) => jsonResponse(200, dashboard(url.searchParams.get("month") ?? currentBusinessMonth())),
    "GET /api/v1/balances/daily": () => jsonResponse(200, { items: [], currency: "VND" }),
    "GET /api/v1/transactions": () =>
      jsonResponse(200, {
        items: [tx({ type: "INCOME", amount: "10000000.00", jar: POOL, category: null, counterparty: "ACME" })],
        nextCursor: null,
      }),
  });
}

const plain = (s: string | null) => (s ?? "").replace(/\s/g, " ");

describe("Dashboard", () => {
  it("shows the month from one GET /dashboard: balances, spending per category and per jar", async () => {
    const { sent } = api();
    renderAt("/", <Dashboard />);

    expect(plain((await screen.findByTestId("total-balance")).textContent)).toBe("16.800.000 ₫");
    expect(screen.getByText("Ăn uống")).toBeInTheDocument();
    expect(screen.getByText("Uncategorized")).toBeInTheDocument(); // spending without a category
    expect(screen.getByRole("link", { name: /Necessities/ })).toHaveAttribute("href", `/inbox?jarId=${NEC.id}`);
    expect(await screen.findByText("ACME")).toBeInTheDocument();
    // no ?month=: the server picks the current month (in Vietnam time), the UI does not send one
    expect(new URL(sent("GET", "/api/v1/dashboard")[0].url).searchParams.has("month")).toBe(false);
  });

  it("goes back one month at a time and never forward past the current month", async () => {
    const { sent } = api();
    renderAt("/", <Dashboard />);
    const thisMonth = currentBusinessMonth();

    expect(screen.getByRole("button", { name: "Next month" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Previous month" }));

    await waitFor(() =>
      expect(new URL(sent("GET", "/api/v1/dashboard").at(-1)!.url).searchParams.get("month")).toBe(
        shiftMonth(thisMonth, -1),
      ),
    );
    expect(screen.getByRole("button", { name: "Next month" })).toBeEnabled();
  });
});
