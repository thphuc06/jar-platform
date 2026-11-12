import { describe, expect, it } from "vitest";
import { buildAnswers, RISK_QUESTIONS, riskLevelLabel } from "./riskQuestionnaire";

describe("risk questionnaire (contract with jars-api RiskQuestionnaire)", () => {
  it("has five questions with the option counts the server scores: 3, 4, 3, 3, 4", () => {
    expect(RISK_QUESTIONS.map((q) => q.options.length)).toEqual([3, 4, 3, 3, 4]);
  });

  it("keeps the order of the topics: risk, horizon, liquidity, experience, objective", () => {
    expect(RISK_QUESTIONS.map((q) => q.title)).toEqual([
      "Khẩu vị rủi ro",
      "Thời gian đầu tư",
      "Nhu cầu thanh khoản",
      "Kinh nghiệm",
      "Mục tiêu",
    ]);
  });
});

describe("buildAnswers", () => {
  it("returns the option index of every question, in question order", () => {
    expect(buildAnswers({ 0: 2, 1: 3, 2: 2, 3: 2, 4: 1 })).toEqual([2, 3, 2, 2, 1]);
    // insertion order of the object does not matter
    expect(buildAnswers({ 4: 0, 3: 0, 2: 0, 1: 0, 0: 0 })).toEqual([0, 0, 0, 0, 0]);
  });

  it("returns null while any question is unanswered", () => {
    expect(buildAnswers({})).toBeNull();
    expect(buildAnswers({ 0: 1, 1: 1, 2: 1, 3: 1 })).toBeNull();
  });

  it("returns null for an option that does not exist", () => {
    expect(buildAnswers({ 0: 3, 1: 0, 2: 0, 3: 0, 4: 0 })).toBeNull();
    expect(buildAnswers({ 0: -1, 1: 0, 2: 0, 3: 0, 4: 0 })).toBeNull();
  });
});

describe("riskLevelLabel", () => {
  it("translates the three levels and passes unknown ones through", () => {
    expect(riskLevelLabel("CONSERVATIVE")).toBe("Thận trọng");
    expect(riskLevelLabel("BALANCED")).toBe("Cân bằng");
    expect(riskLevelLabel("GROWTH")).toBe("Tăng trưởng");
    expect(riskLevelLabel("OTHER")).toBe("OTHER");
  });
});
