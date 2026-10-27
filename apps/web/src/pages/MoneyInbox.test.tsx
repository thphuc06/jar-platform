import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { categories, FOOD, jars, NEC, PLAY, POOL, posting, tx } from "@/test/fixtures";
import { fakeApi, jsonResponse, renderAt } from "@/test/render";
import MoneyInbox from "./MoneyInbox";

afterEach(() => vi.unstubAllGlobals());

const lunch = tx({ counterparty: "Phở Hòa", occurredAt: "2026-09-20T05:00:00Z" });
const salary = tx({ type: "INCOME", amount: "10000000.00", jar: POOL, category: null, counterparty: "ACME" });
const allocation = tx({ type: "JAR_TRANSFER", amount: "2000000.00", jar: POOL, toJar: PLAY, category: null });
const old = tx({ counterparty: "Cà phê", occurredAt: "2026-08-01T01:00:00Z" });

function api(pages = { first: [lunch, salary, allocation], next: [old] }) {
  return fakeApi({
    "GET /api/v1/categories": () => jsonResponse(200, categories),
    "GET /api/v1/jars": () => jsonResponse(200, jars),
    "GET /api/v1/transactions": (url) =>
      url.searchParams.get("cursor") === "c2"
        ? jsonResponse(200, { items: pages.next, nextCursor: null })
        : jsonResponse(200, { items: pages.first, nextCursor: "c2" }),
  });
}

const lastQuery = (requests: Request[]) => new URL(requests[requests.length - 1].url).searchParams;

describe("Money Inbox", () => {
  it("loads older pages with the cursor the API returned, then stops at the last page", async () => {
    const { sent } = api();
    renderAt("/inbox", <MoneyInbox />);

    expect(await screen.findByText("Phở Hòa")).toBeInTheDocument();
    expect(screen.getByText("Pool → Play")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /load older/i }));

    expect(await screen.findByText("Cà phê")).toBeInTheDocument();
    const lists = sent("GET", "/api/v1/transactions");
    expect(lists.map((r) => new URL(r.url).searchParams.get("cursor"))).toEqual([null, "c2"]);
    expect(lastQuery(lists).get("limit")).toBe("30");
    expect(screen.queryByRole("button", { name: /load older/i })).not.toBeInTheDocument();
  });

  it("filters on the server, and keeps the filter in the URL so a link to one jar works", async () => {
    const { sent } = api();
    renderAt(`/inbox?jarId=${NEC.id}`, <MoneyInbox />);

    expect(await screen.findByText(/Jar: Necessities/)).toBeInTheDocument();
    expect(lastQuery(sent("GET", "/api/v1/transactions")).get("jarId")).toBe(NEC.id);

    await userEvent.click(within(screen.getByRole("group", { name: "Category" })).getByText("Ăn uống"));
    await waitFor(() => expect(lastQuery(sent("GET", "/api/v1/transactions")).get("categoryId")).toBe(FOOD));
    expect(lastQuery(sent("GET", "/api/v1/transactions")).get("jarId")).toBe(NEC.id);
  });

  it("sends only the changed field when a transaction is edited (merge patch), then reloads the list", async () => {
    const patches: unknown[] = [];
    const { sent } = fakeApi({
      "GET /api/v1/categories": () => jsonResponse(200, categories),
      "GET /api/v1/jars": () => jsonResponse(200, jars),
      "GET /api/v1/transactions": () => jsonResponse(200, { items: [lunch], nextCursor: null }),
      [`PATCH /api/v1/transactions/${lunch.id}`]: async (_url, request) => {
        patches.push(await request.json());
        return jsonResponse(200, posting(lunch));
      },
    });
    const user = userEvent.setup();
    renderAt("/inbox", <MoneyInbox />);

    await user.click(await screen.findByText("Phở Hòa"));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("Note"), "with the team");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(patches).toEqual([{ note: "with the team" }]));
    await waitFor(() => expect(sent("GET", "/api/v1/transactions")).toHaveLength(2));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("clears the category with an explicit null and only offers categories of the right kind", async () => {
    const patches: unknown[] = [];
    fakeApi({
      "GET /api/v1/categories": () => jsonResponse(200, categories),
      "GET /api/v1/jars": () => jsonResponse(200, jars),
      "GET /api/v1/transactions": () => jsonResponse(200, { items: [lunch], nextCursor: null }),
      [`PATCH /api/v1/transactions/${lunch.id}`]: async (_url, request) => {
        patches.push(await request.json());
        return jsonResponse(200, posting(lunch));
      },
    });
    const user = userEvent.setup();
    renderAt("/inbox", <MoneyInbox />);

    await user.click(await screen.findByText("Phở Hòa"));
    const dialog = await screen.findByRole("dialog");
    const category = within(dialog).getByLabelText("Category");
    expect(within(category).queryByText("Lương")).not.toBeInTheDocument(); // an income category on an expense
    await user.selectOptions(category, "No category");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(patches).toEqual([{ categoryId: null }]));
  });

  it("lets a jar transfer change only its note, and shows the server's reason when an edit is refused", async () => {
    fakeApi({
      "GET /api/v1/categories": () => jsonResponse(200, categories),
      "GET /api/v1/jars": () => jsonResponse(200, jars),
      "GET /api/v1/transactions": () => jsonResponse(200, { items: [allocation], nextCursor: null }),
      [`PATCH /api/v1/transactions/${allocation.id}`]: () =>
        jsonResponse(409, {
          type: "https://jars.local/problems/concurrent-modification",
          title: "Conflict",
          status: 409,
          detail: "The data was changed by another request at the same time; please retry",
        }),
    });
    const user = userEvent.setup();
    renderAt("/inbox", <MoneyInbox />);

    await user.click(await screen.findByText("Pool → Play"));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).queryByLabelText("Category")).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/jar/i)).not.toBeInTheDocument();
    await user.type(within(dialog).getByLabelText("Note"), "x");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    expect((await screen.findAllByText(/changed by another request/)).length).toBeGreaterThan(0);
    expect(screen.getByRole("dialog")).toBeInTheDocument(); // stays open, the user's input is not lost
  });
});
