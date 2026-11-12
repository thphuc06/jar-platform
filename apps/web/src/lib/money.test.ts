import { describe, expect, it } from "vitest";
import {
  compareMoney,
  formatAmount,
  formatGrouped,
  formatMoney,
  fromMinor,
  isNegative,
  isZero,
  moneyToNumber,
  parseVndInput,
  percentOf,
  sanitizeDigits,
  sumMoney,
  toMinor,
  type Money,
} from "./money";

// Intl separates the number and the symbol with a no-break space; compare with a plain one
const plain = (text: string) => text.replace(/[\u00a0\u202f]/g, " ");
const vnd = (amount: string): Money => ({ amount, currency: "VND" });

describe("formatMoney", () => {
  it("formats the {amount, currency} strings with vi-VN grouping", () => {
    expect(plain(formatMoney(vnd("85000.00")))).toBe("85.000 ₫");
    expect(plain(formatMoney(vnd("125430000.00")))).toBe("125.430.000 ₫");
    expect(plain(formatMoney(vnd("0.00")))).toBe("0 ₫");
  });

  it("matches Intl.NumberFormat('vi-VN') for the same value", () => {
    const expected = new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND" }).format(2500000);
    expect(formatMoney(vnd("2500000.00"))).toBe(expected);
  });

  it("keeps the exact digits of an amount a double cannot hold", () => {
    expect(plain(formatMoney(vnd("12345678901234567.00")))).toBe("12.345.678.901.234.567 ₫");
  });

  it("shows decimals only when they are not zero", () => {
    expect(plain(formatMoney(vnd("85000.50")))).toBe("85.000,5 ₫");
  });

  it("prints a minus for a negative balance and can drop the sign", () => {
    expect(plain(formatMoney(vnd("-120000.00")))).toBe("-120.000 ₫");
    expect(plain(formatMoney(vnd("-120000.00"), { signDisplay: "never" }))).toBe("120.000 ₫");
    expect(plain(formatMoney(vnd("20000000.00"), { signDisplay: "always" }))).toBe("+20.000.000 ₫");
  });

  it("does not throw on missing or malformed input", () => {
    expect(formatMoney(null)).toBe("-");
    expect(formatMoney(undefined)).toBe("-");
    expect(formatMoney(vnd("abc"))).toBe("-");
  });

  it("falls back to the digits for an unknown currency code", () => {
    expect(formatMoney({ amount: "10.00", currency: "???" })).toBe("10.00 ???");
  });

  it("formats numbers (roadmap payload) and grouped digits", () => {
    expect(plain(formatAmount(3237660))).toBe("3.237.660 ₫");
    expect(formatGrouped("1500000")).toBe("1.500.000");
    expect(formatGrouped("")).toBe("");
  });
});

describe("exact arithmetic on minor units", () => {
  it("converts to and from minor units", () => {
    expect(toMinor("85000.00")).toBe(8500000n);
    expect(toMinor("0.5")).toBe(50n);
    expect(toMinor("-12.34")).toBe(-1234n);
    expect(fromMinor(8500000n)).toBe("85000.00");
    expect(fromMinor(-5n)).toBe("-0.05");
    expect(fromMinor(0n)).toBe("0.00");
  });

  it("rejects more than two decimals and non-numbers", () => {
    expect(() => toMinor("1.234")).toThrow(RangeError);
    expect(() => toMinor("1e3")).toThrow(RangeError);
    expect(() => toMinor("")).toThrow(RangeError);
  });

  it("sums without the floating point error of 0.1 + 0.2", () => {
    const total = sumMoney([vnd("0.10"), vnd("0.20")]);
    expect(total).toEqual(vnd("0.30"));
    expect(sumMoney([])).toEqual(vnd("0.00"));
    expect(sumMoney([vnd("9007199254740993.00"), vnd("1.00")]).amount).toBe("9007199254740994.00");
  });

  it("refuses to add different currencies", () => {
    expect(() => sumMoney([{ amount: "1.00", currency: "USD" }])).toThrow(RangeError);
  });

  it("compares and classifies", () => {
    expect(compareMoney(vnd("2.00"), vnd("10.00"))).toBe(-1);
    expect(compareMoney(vnd("10.00"), vnd("10.00"))).toBe(0);
    expect(compareMoney(vnd("11.00"), vnd("10.00"))).toBe(1);
    expect(isNegative(vnd("-0.01"))).toBe(true);
    expect(isZero(vnd("0.00"))).toBe(true);
  });

  it("converts to a number and a percentage for charts only", () => {
    expect(moneyToNumber(vnd("85000.00"))).toBe(85000);
    expect(moneyToNumber(null)).toBe(0);
    expect(percentOf(vnd("25.00"), vnd("100.00"))).toBe(25);
    expect(percentOf(vnd("250.00"), vnd("100.00"))).toBe(100);
    expect(percentOf(vnd("-5.00"), vnd("100.00"))).toBe(0);
    expect(percentOf(vnd("5.00"), vnd("0.00"))).toBe(0);
  });
});

describe("user input", () => {
  it("accepts whole VND with grouping and strips it", () => {
    expect(parseVndInput("1.500.000")).toBe("1500000");
    expect(parseVndInput(" 85 000 ")).toBe("85000");
    expect(parseVndInput("007")).toBe("7");
  });

  it("rejects empty, zero and non-numeric input", () => {
    expect(parseVndInput("")).toBeNull();
    expect(parseVndInput("0")).toBeNull();
    expect(parseVndInput("000")).toBeNull();
    expect(parseVndInput("12a")).toBeNull();
    expect(parseVndInput("-5")).toBeNull();
  });

  it("keeps digits only while typing", () => {
    expect(sanitizeDigits("1a2.3,4")).toBe("1234");
    expect(sanitizeDigits("00012")).toBe("12");
    expect(sanitizeDigits("abc")).toBe("");
  });
});
