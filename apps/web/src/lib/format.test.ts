import { describe, expect, it } from "vitest";
import { formatVND } from "./format";

// Intl puts a narrow no-break space before the currency sign; compare on digits and sign only
const plain = (s: string) => s.replace(/\s/g, " ");

describe("formatVND", () => {
  it("formats the API's decimal strings in Vietnamese style", () => {
    expect(plain(formatVND("85000.00"))).toBe("85.000 ₫");
    expect(plain(formatVND(500_000))).toBe("500.000 ₫");
  });

  it("keeps every digit of a 17-digit amount, which a JS number would round", () => {
    const amount = "12345678901234567.00"; // the largest size @VndAmount allows

    expect(plain(formatVND(amount))).toBe("12.345.678.901.234.567 ₫");
    expect(plain(formatVND(Number(amount)))).toBe("12.345.678.901.234.568 ₫");
  });
});
