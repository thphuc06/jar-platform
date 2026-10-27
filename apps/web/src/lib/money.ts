/**
 * Money typed (or pasted) by the user, kept as a string of whole VND (BR-5: no decimals, at most 17 digits).
 * "1.500.000", "1,500,000", "1 500 000" and "1500000" are the same amount: a separator followed by exactly three
 * digits groups thousands. "85000.00" (an amount copied from the API) is 85000, NOT 8500000: one or two digits after
 * the last separator are a decimal part, accepted only when it is zero. Anything else returns null.
 */
export function parseAmount(text: string): string | null {
  const trimmed = text.trim();
  let digits: string;
  if (/^\d{1,3}([.,\s_]\d{3})+$/.test(trimmed) || /^\d+$/.test(trimmed)) {
    digits = trimmed.replace(/[.,\s_]/g, "");
  } else {
    const decimal = /^(\d+)[.,](\d{1,2})$/.exec(trimmed);
    if (!decimal || !/^0+$/.test(decimal[2])) {
      return null; // "85000.5": VND has no fractions
    }
    digits = decimal[1];
  }
  const normalized = digits.replace(/^0+/, "");
  return normalized === "" || normalized.length > 17 ? null : normalized;
}

/** Whole VND of an API amount ("2130000.00" → 2130000n). BigInt: exact for every amount the API allows. */
export function wholeVnd(amount: string): bigint {
  return BigInt(amount.split(".")[0]);
}

/** a <= b, exactly (no JS number rounding above 2^53). */
export function fits(amount: string, available: string): boolean {
  return wholeVnd(amount) <= wholeVnd(available);
}

/**
 * The share of `amount` for a jar's target percentage, rounded DOWN to whole VND, e.g. 55 % of the unallocated money.
 * The percentage (NUMERIC(5,2)) is turned into basis points first, so the whole computation stays in integers.
 */
export function shareOf(amount: string, targetPct: number): string {
  const basisPoints = BigInt(Math.round(targetPct * 100));
  return ((wholeVnd(amount) * basisPoints) / 10000n).toString();
}
