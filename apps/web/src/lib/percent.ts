/** Percentages of the jars (`targetPct`): 0..100 with at most two decimals, as the API validates them. */

/** "12,5" or "12.5" -> 12.5; null when it is not a number in 0..100 with at most two decimals. */
export function parsePct(text: string): number | null {
  const normalized = text.trim().replace(",", ".");
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(normalized)) return null;
  const value = Number(normalized);
  return value >= 0 && value <= 100 ? value : null;
}

/** Sum of percentages without 0.1 + 0.2 noise (rounded to two decimals). */
export function sumPct(values: readonly number[]): number {
  return Math.round(values.reduce((total, v) => total + v, 0) * 100) / 100;
}

export function formatPct(value: number): string {
  return `${new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 2 }).format(value)}%`;
}

/** An income split must add up to exactly 100 % (api-contract: PATCH /jars/{id}, POST /budget/allocations). */
export function isFullAllocation(values: readonly number[]): boolean {
  return sumPct(values) === 100;
}
