import { describe, expect, it } from "vitest";
import { formatPct, isFullAllocation, parsePct, sumPct } from "./percent";

describe("parsePct", () => {
  it("accepts 0..100 with a dot or a comma and at most two decimals", () => {
    expect(parsePct("55")).toBe(55);
    expect(parsePct("12,5")).toBe(12.5);
    expect(parsePct(" 7.25 ")).toBe(7.25);
    expect(parsePct("0")).toBe(0);
    expect(parsePct("100")).toBe(100);
  });

  it("rejects everything else", () => {
    expect(parsePct("")).toBeNull();
    expect(parsePct("101")).toBeNull();
    expect(parsePct("-5")).toBeNull();
    expect(parsePct("1.234")).toBeNull();
    expect(parsePct("abc")).toBeNull();
  });
});

describe("sumPct and isFullAllocation", () => {
  it("adds without floating point noise", () => {
    expect(sumPct([0.1, 0.2])).toBe(0.3);
    expect(sumPct([55, 10, 10, 10, 10, 5])).toBe(100);
    expect(sumPct([])).toBe(0);
  });

  it("requires exactly 100", () => {
    expect(isFullAllocation([55, 10, 10, 10, 10, 5])).toBe(true);
    expect(isFullAllocation([33.33, 33.33, 33.34])).toBe(true);
    expect(isFullAllocation([55, 10, 10, 10, 10])).toBe(false);
    expect(isFullAllocation([60, 50])).toBe(false);
  });
});

describe("formatPct", () => {
  it("uses the Vietnamese decimal comma", () => {
    expect(formatPct(55)).toBe("55%");
    expect(formatPct(12.5)).toBe("12,5%");
  });
});
