import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { jsonResponse, renderAt } from "@/test/render";
import Onboarding from "./Onboarding";

afterEach(() => vi.unstubAllGlobals());

const profile = {
  answers: {
    riskTolerance: "MODERATE",
    horizon: "YEARS_5_TO_10",
    liquidity: "FLEXIBLE",
    experience: "EXPERIENCED",
    mainGoal: "LONG_TERM_GROWTH",
  },
  score: 8,
  maxScore: 13,
  riskLevel: "BALANCED",
  capped: false,
  questionnaireVersion: 1,
  assessedAt: "2026-10-27T03:00:00Z",
};

describe("Onboarding", () => {
  it("sends the five answers as the API's enum values and lets the server score them", async () => {
    const fetch = vi.fn(async (_request: Request) => jsonResponse(200, profile));
    vi.stubGlobal("fetch", fetch);
    const user = userEvent.setup();
    renderAt("/onboarding", <Onboarding />);

    await user.click(screen.getByRole("button", { name: /get started/i }));
    for (const option of [
      "Moderate - Balanced approach",
      "5-10 years",
      "Flexible - no rush",
      "Intermediate - Some experience",
      "Grow wealth long-term",
    ]) {
      await user.click(screen.getByText(option));
      await user.click(screen.getByRole("button", { name: /continue|complete profile/i }));
    }

    expect(await screen.findByTestId("location")).toHaveTextContent(/^\/$/);
    const request = fetch.mock.calls[0][0];
    expect(request.method).toBe("PUT");
    expect(new URL(request.url).pathname).toBe("/api/v1/me/risk-profile");
    expect(await request.json()).toEqual(profile.answers);
    expect(await screen.findByText("Risk profile: BALANCED")).toBeInTheDocument();
  });

  it("stays on the questionnaire and shows the problem when the server rejects the answers", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        jsonResponse(422, {
          type: "https://jars.local/problems/validation-failed",
          title: "Unprocessable Content",
          status: 422,
          detail: "The request has invalid fields",
        }),
      ),
    );
    const user = userEvent.setup();
    renderAt("/onboarding", <Onboarding />);

    await user.click(screen.getByRole("button", { name: /get started/i }));
    for (let step = 0; step < 5; step++) {
      await user.click(screen.getAllByText(/./, { selector: "span.text-sm" })[0]);
      await user.click(screen.getByRole("button", { name: /continue|complete profile/i }));
    }

    expect(await screen.findByText("The request has invalid fields")).toBeInTheDocument();
    expect(screen.queryByTestId("location")).not.toBeInTheDocument();
  });
});
