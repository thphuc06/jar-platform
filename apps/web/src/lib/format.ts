const vnd = new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND", maximumFractionDigits: 0 });

/**
 * Formats money as sent by the API: a decimal STRING such as "85000.00". Intl.NumberFormat (ES2023) formats a numeric
 * string exactly; converting it to a JS number first would round anything above 2^53 ("…567" would print "…568").
 * Numbers are accepted for UI-only values such as quick-amount buttons.
 */
export function formatVND(amount: string | number): string {
  return vnd.format(typeof amount === "string" ? (amount as `${number}`) : amount);
}

/**
 * The business calendar is Vietnam time (dashboard months, P1.9): the server counts 2026-08-31T17:30Z in SEPTEMBER, so the UI
 * must show that transaction as 01/09, whatever time zone the browser is in.
 */
export const BUSINESS_TIME_ZONE = "Asia/Ho_Chi_Minh";

const day = new Intl.DateTimeFormat("en-GB", {
  timeZone: BUSINESS_TIME_ZONE,
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
});
const dayTime = new Intl.DateTimeFormat("en-GB", {
  timeZone: BUSINESS_TIME_ZONE,
  day: "2-digit",
  month: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
});
const monthName = new Intl.DateTimeFormat("en-US", { timeZone: "UTC", month: "long", year: "numeric" });
const monthShort = new Intl.DateTimeFormat("en-US", { timeZone: "UTC", month: "short" });

/** "2026-08-31T17:30:00Z" → "01/09/2026" (Vietnam date). */
export function formatDate(instant: string): string {
  return day.format(new Date(instant));
}

/** "2026-08-31T17:30:00Z" → "01/09, 00:30" (Vietnam time). */
export function formatDateTime(instant: string): string {
  return dayTime.format(new Date(instant));
}

/** "2026-09" → "September 2026". A month has no time zone, so it is read as a plain calendar value. */
export function formatMonth(yearMonth: string): string {
  return monthName.format(new Date(`${yearMonth}-01T00:00:00Z`));
}

/** "2026-09" → "Sep". */
export function formatMonthShort(yearMonth: string): string {
  return monthShort.format(new Date(`${yearMonth}-01T00:00:00Z`));
}

/** "2026-01" shifted by -1 → "2025-12". Pure calendar arithmetic, no time zone involved. */
export function shiftMonth(yearMonth: string, delta: number): string {
  const [year, month] = yearMonth.split("-").map(Number);
  const index = year * 12 + (month - 1) + delta;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, "0")}`;
}

const businessYearMonth = new Intl.DateTimeFormat("en-CA", { timeZone: BUSINESS_TIME_ZONE, year: "numeric", month: "2-digit" });

/** The current month in Vietnam time, "YYYY-MM": the same month GET /dashboard uses when none is given. */
export function currentBusinessMonth(now: Date = new Date()): string {
  return businessYearMonth.format(now).slice(0, 7);
}

/** "2026-09-01" (a LocalDate from the API, already a Vietnam date) → "01/09/2026"; no time zone conversion. */
export function formatLocalDate(date: string): string {
  const [year, month, dayOfMonth] = date.split("-");
  return `${dayOfMonth}/${month}/${year}`;
}
