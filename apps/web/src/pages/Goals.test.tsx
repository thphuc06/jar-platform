import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { jars, NEC } from "@/test/fixtures";
import { fakeApi, jsonResponse, renderAt } from "@/test/render";
import Goals from "./Goals";

afterEach(() => vi.unstubAllGlobals());

const laptop = {
  id: "cccccccc-0000-7000-8000-000000000001",
  name: "Laptop",
  targetAmount: "30000000.00",
  targetDate: "2027-06-30",
  status: "ACTIVE" as const,
  fundingJar: { id: NEC.id, code: "NEC", name: "Necessities", balance: "2130000.00", archived: false },
  progressPct: 7,
};

function api(bodies: unknown[]) {
  return fakeApi({
    "GET /api/v1/goals": () => jsonResponse(200, { items: [laptop] }),
    "GET /api/v1/jars": () => jsonResponse(200, jars),
    "POST /api/v1/goals": async (_url, request) => {
      bodies.push(await request.json());
      return jsonResponse(422, {
        type: "https://jars.local/problems/target-date-not-in-future",
        title: "Unprocessable Content",
        status: 422,
        detail: "The target date must be after today (Vietnam time)",
      });
    },
    [`PATCH /api/v1/goals/${laptop.id}`]: async (_url, request) => {
      bodies.push(await request.json());
      return jsonResponse(200, laptop);
    },
    [`DELETE /api/v1/goals/${laptop.id}`]: () => new Response(null, { status: 204 }),
  });
}

describe("Goals", () => {
  it("lists goals with the progress of their funding jar", async () => {
    api([]);
    renderAt("/goals", <Goals />);

    expect(await screen.findByText("Laptop")).toBeInTheDocument();
    expect(screen.getByText(/30.000.000 ₫ by 30\/06\/2027/)).toBeInTheDocument();
    expect(screen.getByText(/7% · Necessities holds/)).toBeInTheDocument();
  });

  it("creates a goal and shows the server's reason when it is refused (the date rule is checked in Vietnam time)", async () => {
    const bodies: unknown[] = [];
    api(bodies);
    const user = userEvent.setup();
    renderAt("/goals", <Goals />);

    await user.click(await screen.findByRole("button", { name: /new goal/i }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("Name"), "Emergency fund");
    await user.type(within(dialog).getByLabelText("Target (VND)"), "50.000.000");
    await user.type(within(dialog).getByLabelText("Target date"), "2026-10-27");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    expect((await screen.findAllByText(/must be after today/)).length).toBeGreaterThan(0);
    expect(bodies).toEqual([{ name: "Emergency fund", targetAmount: "50000000", targetDate: "2026-10-27", fundingJarId: null }]);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("edits with a merge patch: only the changed fields, null to detach the jar", async () => {
    const bodies: unknown[] = [];
    api(bodies);
    const user = userEvent.setup();
    renderAt("/goals", <Goals />);

    await user.click(await screen.findByRole("button", { name: /edit/i }));
    const dialog = await screen.findByRole("dialog");
    await user.selectOptions(within(dialog).getByLabelText("Funding jar"), "None yet");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(bodies).toEqual([{ fundingJarId: null }]));
  });

  it("marks a goal achieved and archives it after a confirmation", async () => {
    const bodies: unknown[] = [];
    const { sent } = api(bodies);
    vi.stubGlobal("confirm", vi.fn(() => true));
    const user = userEvent.setup();
    renderAt("/goals", <Goals />);

    await user.click(await screen.findByRole("button", { name: /mark achieved/i }));
    await waitFor(() => expect(bodies).toEqual([{ status: "ACHIEVED" }]));
    await user.click(screen.getByRole("button", { name: "Archive Laptop" }));

    await waitFor(() => expect(sent("DELETE", `/api/v1/goals/${laptop.id}`)).toHaveLength(1));
  });
});
