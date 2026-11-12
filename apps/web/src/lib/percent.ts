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

/**
 * Divides a whole-VND income (`total`, digits only) by percentages that add up to exactly 100, in whole VND, so that the
 * parts add up to exactly `total`: the server rejects an allocation that is off by even one dong.
 *
 * Each part is rounded down, then the leftover dong go one by one to the largest fractional remainders (ties: the
 * earlier jar). BigInt, because `total * basisPoints` can exceed 2^53.
 */
export function splitByPercent(total: string, pcts: readonly number[]): string[] {
  const whole = BigInt(total);
  const basisPoints = pcts.map((p) => BigInt(Math.round(p * 100)));
  const parts = basisPoints.map((bp) => (whole * bp) / 10000n);
  const remainders = basisPoints.map((bp, i) => ({ i, remainder: (whole * bp) % 10000n }));
  remainders.sort((a, b) => (a.remainder === b.remainder ? a.i - b.i : a.remainder > b.remainder ? -1 : 1));
  let leftover = whole - parts.reduce((sum, part) => sum + part, 0n);
  for (const { i } of remainders) {
    if (leftover <= 0n) break;
    parts[i] += 1n;
    leftover -= 1n;
  }
  return parts.map(String);
}
