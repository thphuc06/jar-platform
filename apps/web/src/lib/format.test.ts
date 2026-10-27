import { describe, expect, it } from "vitest";
import {
  currentBusinessMonth,
  formatDate,
  formatDateTime,
  formatLocalDate,
  formatMonth,
  formatMonthShort,
  formatVND,
  shiftMonth,
} from "./format";

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

describe("business calendar (Vietnam time)", () => {
  it("shows an instant as the Vietnam date the server counts it in", () => {
    // 17:30 UTC on 31 August is 00:30 on 1 September in Vietnam: the dashboard counts it in September
    expect(formatDate("2026-08-31T17:30:00Z")).toBe("01/09/2026");
    expect(formatDateTime("2026-08-31T17:30:00Z")).toBe("01/09, 00:30");
    expect(currentBusinessMonth(new Date("2026-08-31T17:30:00Z"))).toBe("2026-09");
    expect(currentBusinessMonth(new Date("2026-08-31T16:59:59Z"))).toBe("2026-08");
  });

  it("moves between months across year boundaries", () => {
    expect(shiftMonth("2026-01", -1)).toBe("2025-12");
    expect(shiftMonth("2026-12", 1)).toBe("2027-01");
    expect(shiftMonth("2026-09", -6)).toBe("2026-03");
  });

  it("formats months and API dates without shifting them by a time zone", () => {
    expect(formatMonth("2026-09")).toBe("September 2026");
    expect(formatMonthShort("2026-01")).toBe("Jan");
    expect(formatLocalDate("2026-09-01")).toBe("01/09/2026");
  });
});
