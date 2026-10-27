import { screen, waitFor } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { categories, FOOD, jars, NEC, posting, SALARY, tx } from "@/test/fixtures";
import { fakeApi, jsonResponse, renderAt } from "@/test/render";
import NewTransaction from "./NewTransaction";

afterEach(() => vi.unstubAllGlobals());

const created = (headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(posting(tx())), {
    status: 201,
    headers: { "Content-Type": "application/json", Location: "/api/v1/transactions/x", ...headers },
  });

const insufficient = () =>
  jsonResponse(422, {
    type: "https://jars.local/problems/insufficient-jar-balance",
    title: "Unprocessable Content",
    status: 422,
    detail: "Jar NEC holds 2130000.00 VND, 85000 VND requested",
  });

async function fillPayment(user: UserEvent, amount = "85.000") {
  await user.click(await screen.findByText("Necessities"));
  await user.selectOptions(screen.getByLabelText("Recipient bank"), "Vietcombank");
  await user.type(screen.getByLabelText("Account number"), "0123456789");
  await user.type(screen.getByLabelText("Amount (VND)"), amount);
  await user.selectOptions(screen.getByLabelText("Category"), "Ăn uống");
}

describe("New transaction", () => {
  it("records a payment from a jar with the amount as a string and a fresh Idempotency-Key", async () => {
    const { sent } = fakeApi({
      "GET /api/v1/jars": () => jsonResponse(200, jars),
      "GET /api/v1/categories": () => jsonResponse(200, categories),
      "POST /api/v1/transactions": () => created(),
    });
    const user = userEvent.setup();
    renderAt("/transfer", <NewTransaction />);

    await fillPayment(user);
    await user.click(screen.getByRole("button", { name: "Pay" }));

    expect(await screen.findByTestId("location")).toHaveTextContent(/^\/$/);
    const [request] = sent("POST", "/api/v1/transactions");
    expect(await request.json()).toEqual({
      type: "EXPENSE",
      amount: "85000",
      categoryId: FOOD,
      note: null,
      jarId: NEC.id,
      counterparty: "0123456789 (Vietcombank)",
    });
    expect(request.headers.get("Idempotency-Key")).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("retries a lost response with the SAME key, and says so when the server replays the stored result", async () => {
    let calls = 0;
    const { sent } = fakeApi({
      "GET /api/v1/jars": () => jsonResponse(200, jars),
      "GET /api/v1/categories": () => jsonResponse(200, categories),
      // the first attempt reached the server and was recorded, but the answer never arrived
      "POST /api/v1/transactions": () => {
        calls += 1;
        if (calls === 1) throw new TypeError("Failed to fetch");
        return created({ "Idempotent-Replayed": "true" });
      },
    });
    const user = userEvent.setup();
    renderAt("/transfer", <NewTransaction />);

    await fillPayment(user);
    await user.click(screen.getByRole("button", { name: "Pay" }));

    expect(await screen.findByText("Already recorded", {}, { timeout: 3000 })).toBeInTheDocument();
    const keys = sent("POST", "/api/v1/transactions").map((r) => r.headers.get("Idempotency-Key"));
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it("keeps the key when Save is pressed again with the same details, and takes a new one once they change", async () => {
    const { sent } = fakeApi({
      "GET /api/v1/jars": () => jsonResponse(200, jars),
      "GET /api/v1/categories": () => jsonResponse(200, categories),
      "POST /api/v1/transactions": () => insufficient(),
    });
    const user = userEvent.setup();
    renderAt("/transfer", <NewTransaction />);

    await fillPayment(user);
    const pay = screen.getByRole("button", { name: "Pay" });
    await user.click(pay);
    expect((await screen.findAllByText(/holds 2130000.00 VND/)).length).toBeGreaterThan(0);
    await user.click(pay);
    await waitFor(() => expect(sent("POST", "/api/v1/transactions")).toHaveLength(2));
    await user.clear(screen.getByLabelText("Amount (VND)"));
    await user.type(screen.getByLabelText("Amount (VND)"), "90000");
    await user.click(pay);
    await waitFor(() => expect(sent("POST", "/api/v1/transactions")).toHaveLength(3));

    const [first, again, changed] = sent("POST", "/api/v1/transactions").map((r) => r.headers.get("Idempotency-Key"));
    expect(again).toBe(first); // 4xx: no automatic retry, but the user's retry is the same action
    expect(changed).not.toBe(first); // a different request must not reuse the key
  });

  it("does not offer to pay more than the jar holds", async () => {
    fakeApi({
      "GET /api/v1/jars": () => jsonResponse(200, jars),
      "GET /api/v1/categories": () => jsonResponse(200, categories),
    });
    const user = userEvent.setup();
    renderAt("/transfer", <NewTransaction />);

    await fillPayment(user, "2.130.001");

    expect(screen.getByText(/Necessities holds only/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pay" })).toBeDisabled();
  });

  it("records income without a jar (it lands in POOL), then goes on to allocate it", async () => {
    const { sent } = fakeApi({
      "GET /api/v1/jars": () => jsonResponse(200, jars),
      "GET /api/v1/categories": () => jsonResponse(200, categories),
      "POST /api/v1/transactions": () => created(),
    });
    const user = userEvent.setup();
    renderAt("/transfer", <NewTransaction />);

    await user.click(screen.getByRole("button", { name: "Record income" }));
    await user.type(screen.getByLabelText("From"), "ACME");
    await user.type(screen.getByLabelText("Amount (VND)"), "10000000");
    await user.selectOptions(await screen.findByLabelText("Category"), "Lương");
    await user.click(screen.getAllByRole("button", { name: "Record income" }).at(-1)!);

    expect(await screen.findByTestId("location")).toHaveTextContent("/jars");
    expect(await sent("POST", "/api/v1/transactions")[0].json()).toEqual({
      type: "INCOME",
      amount: "10000000",
      categoryId: SALARY,
      note: null,
      counterparty: "ACME",
    });
  });
});
