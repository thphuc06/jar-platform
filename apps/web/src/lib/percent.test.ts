import { describe, expect, it } from "vitest";
import { formatPct, isFullAllocation, parsePct, splitByPercent, sumPct } from "./percent";

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

describe("splitByPercent", () => {
  const sum = (parts: string[]) => parts.reduce((total, part) => total + BigInt(part), 0n);

  it("splits evenly when it divides exactly", () => {
    expect(splitByPercent("20000000", [55, 10, 10, 10, 10, 5])).toEqual(["11000000", "2000000", "2000000", "2000000", "2000000", "1000000"]);
  });

  it("gives the leftover dong to the largest remainders so the parts add up to the total", () => {
    // floors are 33/33/33, one dong is left and goes to the largest remainder (the third share, 33.34 %)
    const parts = splitByPercent("100", [33.33, 33.33, 33.34]);
    expect(sum(parts)).toBe(100n);
    expect(parts).toEqual(["33", "33", "34"]);
  });

  it("always adds up exactly, for awkward amounts and shares", () => {
    const shares = [33.33, 16.67, 25, 12.5, 12.5];
    for (const total of ["1", "7", "999", "1234567", "9999999999999"]) {
      expect(sum(splitByPercent(total, shares))).toBe(BigInt(total));
    }
  });

  it("is exact beyond the range of a double (2^53)", () => {
    const total = "9007199254740993"; // 2^53 + 1
    expect(sum(splitByPercent(total, [50, 50]))).toBe(BigInt(total));
  });

  it("gives a zero share nothing", () => {
    expect(splitByPercent("1000", [100, 0])).toEqual(["1000", "0"]);
  });
});
