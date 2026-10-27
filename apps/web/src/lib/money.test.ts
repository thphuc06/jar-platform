import { describe, expect, it } from "vitest";
import { fits, parseAmount, shareOf, wholeVnd } from "./money";

describe("parseAmount", () => {
  it.each([
    ["1500000", "1500000"],
    ["1.500.000", "1500000"], // Vietnamese thousands separator
    ["1,500,000", "1500000"],
    ["1 500 000", "1500000"],
    ["  85000 ", "85000"],
    ["85000.00", "85000"], // an amount copied from the API: NOT 8500000
    ["85000,0", "85000"],
    ["007", "7"],
    ["12345678901234567", "12345678901234567"], // 17 digits, the most @VndAmount allows
  ])("reads %j as %s VND", (text, expected) => {
    expect(parseAmount(text)).toBe(expected);
  });

  it.each(["", "0", "0.00", "-5", "85000.5", "12.5", "1.50.000", "1e6", "abc", "123456789012345678"])(
    "rejects %j",
    (text) => {
      expect(parseAmount(text)).toBeNull();
    },
  );
});

describe("exact whole-VND arithmetic", () => {
  it("compares amounts above 2^53 exactly", () => {
    expect(fits("9007199254740993", "9007199254740992.00")).toBe(false); // a JS number sees them as equal
    expect(Number("9007199254740993") <= Number("9007199254740992")).toBe(true);
    expect(fits("85000", "85000.00")).toBe(true);
    expect(wholeVnd("2130000.00")).toBe(2130000n);
  });

  it("splits by a target percentage rounding DOWN, in integers", () => {
    expect(shareOf("13000000.00", 55)).toBe("7150000");
    expect(shareOf("1000.00", 33.33)).toBe("333");
    // 55 % of the largest amount: exact with BigInt, off by one with JS numbers
    expect(shareOf("12345678901234567.00", 55)).toBe("6790123395679011");
    // (a literal 12345678901234567 is rejected by ESLint no-loss-of-precision: it cannot be a JS number)
    expect(Math.floor(Number("12345678901234567") * 0.55)).not.toBe(6790123395679011);
  });
});
