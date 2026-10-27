import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { jars, NEC, POOL, PLAY, posting, tx } from "@/test/fixtures";
import { fakeApi, jsonResponse, renderAt } from "@/test/render";
import BudgetJars from "./BudgetJars";

afterEach(() => vi.unstubAllGlobals());

const plain = (s: string | null) => (s ?? "").replace(/\s/g, " ");

function api() {
  return fakeApi({
    "GET /api/v1/jars": () => jsonResponse(200, jars),
    "POST /api/v1/jars/transfers": () =>
      new Response(JSON.stringify(posting(tx({ type: "JAR_TRANSFER", jar: POOL, toJar: NEC }))), {
        status: 201,
        headers: { "Content-Type": "application/json" },
      }),
  });
}

describe("Budget Jars", () => {
  it("shows the unallocated money (the POOL jar) next to the six jars", async () => {
    api();
    renderAt("/jars", <BudgetJars />);

    expect(plain((await screen.findByTestId("unallocated")).textContent)).toBe("Unallocated: 13.000.000 ₫");
    expect(screen.getByText("In jars: 22%")).toBeInTheDocument(); // (16.8 M - 13 M) / 16.8 M, rounded down
    expect(screen.getByText("Necessities")).toBeInTheDocument();
  });

  it("allocates from POOL using the poolJarId of GET /jars, with the jar's target share as a suggestion", async () => {
    const { sent } = api();
    const user = userEvent.setup();
    renderAt("/jars", <BudgetJars />);

    await user.click(await screen.findByRole("button", { name: /allocate/i }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByLabelText("From")).toHaveValue(POOL.id);
    await user.click(within(dialog).getByRole("button", { name: /55 % target of Necessities/ }));
    await user.click(within(dialog).getByRole("button", { name: "Move" }));

    await waitFor(() => expect(sent("POST", "/api/v1/jars/transfers")).toHaveLength(1));
    const [request] = sent("POST", "/api/v1/jars/transfers");
    expect(await request.json()).toEqual({ fromJarId: POOL.id, toJarId: NEC.id, amount: "7150000", note: null });
    expect(request.headers.get("Idempotency-Key")).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("gives each separate move its own key", async () => {
    const { sent } = api();
    const user = userEvent.setup();
    renderAt("/jars", <BudgetJars />);

    for (let i = 0; i < 2; i++) {
      await user.click(await screen.findByRole("button", { name: `Move money out of ${PLAY.name}` }));
      const dialog = await screen.findByRole("dialog");
      await user.type(within(dialog).getByLabelText("Amount (VND)"), "100000");
      await user.click(within(dialog).getByRole("button", { name: "Move" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    }

    const keys = sent("POST", "/api/v1/jars/transfers").map((r) => r.headers.get("Idempotency-Key"));
    expect(keys).toHaveLength(2);
    expect(keys[1]).not.toBe(keys[0]); // same amount twice on purpose = two moves, not a retry
  });
});
