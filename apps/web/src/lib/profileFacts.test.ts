import { describe, expect, it } from "vitest";
import { AGE_BANDS, ageBandLabel, buildProfileFacts, INCOME_SITUATIONS, incomeSituationLabel } from "./profileFacts";

describe("profile facts (contract with jars-api AgeBand and IncomeSituation)", () => {
  it("offers the five age bands and the three income situations, as the server's codes", () => {
    expect(AGE_BANDS.map((o) => o.value)).toEqual(["UNDER_25", "AGE_25_34", "AGE_35_44", "AGE_45_54", "AGE_55_PLUS"]);
    expect(INCOME_SITUATIONS.map((o) => o.value)).toEqual(["REGULAR_SALARY", "IRREGULAR_INCOME", "NO_INCOME_YET"]);
  });

  it("keeps the bands of the brief: under 25, 25-34, 35-44, 45-54, 55 and over", () => {
    expect(AGE_BANDS.map((o) => o.label)).toEqual(["Dưới 25 tuổi", "25-34 tuổi", "35-44 tuổi", "45-54 tuổi", "Từ 55 tuổi trở lên"]);
  });
});

describe("buildProfileFacts", () => {
  it("turns the two chosen option indexes into the codes of PATCH /me", () => {
    expect(buildProfileFacts(1, 2)).toEqual({ ageBand: "AGE_25_34", incomeSituation: "NO_INCOME_YET" });
    expect(buildProfileFacts(0, 0)).toEqual({ ageBand: "UNDER_25", incomeSituation: "REGULAR_SALARY" });
  });

  it("returns null while a question is unanswered or the option does not exist", () => {
    expect(buildProfileFacts(undefined, 1)).toBeNull();
    expect(buildProfileFacts(1, undefined)).toBeNull();
    expect(buildProfileFacts(5, 0)).toBeNull();
    expect(buildProfileFacts(0, -1)).toBeNull();
  });
});

describe("labels", () => {
  it("translates a code, and is null for no answer or an unknown code", () => {
    expect(ageBandLabel("AGE_45_54")).toBe("45-54 tuổi");
    expect(incomeSituationLabel("IRREGULAR_INCOME")).toContain("không đều");
    expect(ageBandLabel(null)).toBeNull();
    expect(incomeSituationLabel(undefined)).toBeNull();
    expect(ageBandLabel("1990")).toBeNull();
  });
});
