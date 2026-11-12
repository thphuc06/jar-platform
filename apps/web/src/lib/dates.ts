import { addMonths, format } from "date-fns";
import { vi } from "date-fns/locale/vi";

export { vi as viLocale };

/** `YYYY-MM` of a date, the month format of the API (dashboard, budget periods). */
export function monthKey(date: Date = new Date()): string {
  return format(date, "yyyy-MM");
}

export function formatDate(iso: string): string {
  return format(new Date(iso), "dd/MM/yyyy");
}

/** `YYYY-MM-DD` of a date, the format of `targetDate`. */
export function isoDate(date: Date): string {
  return format(date, "yyyy-MM-dd");
}

/** `YYYY-MM-DD` of the date `months` after `from` (date-fns clamps 31 Jan + 1 month to 28/29 Feb). */
export function addMonthsIso(from: Date, months: number): string {
  return isoDate(addMonths(from, months));
}

/** Whole months from `from` to the ISO date `to`, at least 0. */
export function monthsBetween(from: Date, to: string): number {
  const target = new Date(`${to}T00:00:00`);
  const months = (target.getFullYear() - from.getFullYear()) * 12 + (target.getMonth() - from.getMonth());
  return Math.max(0, months);
}

export function greeting(hour: number): string {
  if (hour < 11) return "Chào buổi sáng";
  if (hour < 14) return "Chào buổi trưa";
  if (hour < 18) return "Chào buổi chiều";
  return "Chào buổi tối";
}
