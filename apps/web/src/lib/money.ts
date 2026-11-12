/**
 * Money as the API sends it: `{"amount":"85000.00","currency":"VND"}`. The amount is a decimal *string* with two
 * decimals (ADR-0014), so nothing is ever rounded by JavaScript. Convert to a number only to draw a chart.
 */
export interface Money {
  readonly amount: string;
  readonly currency: string;
}

const LOCALE = "vi-VN";
const DECIMAL = /^-?\d+(\.\d+)?$/;
const SCALE = 2;

const formatters = new Map<string, Intl.NumberFormat>();

function currencyFormatter(currency: string, signDisplay: Intl.NumberFormatOptions["signDisplay"]) {
  const key = `${currency}|${signDisplay ?? "auto"}`;
  let formatter = formatters.get(key);
  if (!formatter) {
    formatter = new Intl.NumberFormat(LOCALE, {
      style: "currency",
      currency,
      // VND has no minor unit, but the ledger keeps two decimals: show them only when they are not zero
      minimumFractionDigits: 0,
      maximumFractionDigits: SCALE,
      signDisplay,
    });
    formatters.set(key, formatter);
  }
  return formatter;
}

export interface FormatOptions {
  /** "never" prints the magnitude only (the UI shows the direction with an icon and "+"/"-" itself). */
  signDisplay?: Intl.NumberFormatOptions["signDisplay"];
}

/** "85.000 ₫". The decimal string goes to Intl as it is, so large amounts keep their exact digits. */
export function formatMoney(money: Money | null | undefined, options: FormatOptions = {}): string {
  if (!money) return "-";
  return formatAmount(money.amount, money.currency, options);
}

export function formatAmount(amount: string | number, currency = "VND", options: FormatOptions = {}): string {
  const text = typeof amount === "number" ? String(amount) : amount;
  if (!DECIMAL.test(text)) return "-";
  try {
    return currencyFormatter(currency, options.signDisplay).format(text as `${number}`);
  } catch {
    // unknown currency code: still show the digits
    return `${text} ${currency}`;
  }
}

/** Grouped digits without a currency symbol ("1.500.000"), for input fields and chart axes. */
export function formatGrouped(amount: string): string {
  if (!/^\d+$/.test(amount)) return amount;
  return new Intl.NumberFormat(LOCALE).format(amount as `${number}`);
}

/** Compact axis label: 1.500.000 -> "1,5 tr". Display only. */
export function formatCompact(value: number): string {
  return new Intl.NumberFormat(LOCALE, { notation: "compact", maximumFractionDigits: 1 }).format(value);
}

// ---- exact arithmetic on minor units (scale 2) ------------------------------------------------------------

/** "85000.00" -> 8500000n. Throws on anything that is not a plain decimal with at most two decimals. */
export function toMinor(amount: string): bigint {
  if (!DECIMAL.test(amount)) throw new RangeError(`Not a decimal amount: ${amount}`);
  const negative = amount.startsWith("-");
  const [whole, fraction = ""] = (negative ? amount.slice(1) : amount).split(".");
  if (fraction.length > SCALE) throw new RangeError(`More than ${SCALE} decimals: ${amount}`);
  const minor = BigInt(whole + fraction.padEnd(SCALE, "0"));
  return negative ? -minor : minor;
}

export function fromMinor(minor: bigint): string {
  const negative = minor < 0n;
  const digits = (negative ? -minor : minor).toString().padStart(SCALE + 1, "0");
  const text = `${digits.slice(0, -SCALE)}.${digits.slice(-SCALE)}`;
  return negative ? `-${text}` : text;
}

export function sumMoney(values: readonly Money[], currency = "VND"): Money {
  let total = 0n;
  for (const value of values) {
    if (value.currency !== currency) throw new RangeError(`Currency mismatch: ${value.currency} vs ${currency}`);
    total += toMinor(value.amount);
  }
  return { amount: fromMinor(total), currency };
}

export function compareMoney(a: Money, b: Money): number {
  const diff = toMinor(a.amount) - toMinor(b.amount);
  return diff === 0n ? 0 : diff < 0n ? -1 : 1;
}

export function isNegative(money: Money): boolean {
  return toMinor(money.amount) < 0n;
}

export function isZero(money: Money): boolean {
  return toMinor(money.amount) === 0n;
}

/** Lossy on purpose: for chart axes and progress bars only. Never send the result to the API. */
export function moneyToNumber(money: Money | null | undefined): number {
  return money ? Number(money.amount) : 0;
}

/** `part` as a percentage of `total`, clamped to 0..100, for progress bars. */
export function percentOf(part: Money, total: Money): number {
  const t = Number(total.amount);
  if (!(t > 0)) return 0;
  return Math.max(0, Math.min(100, Math.round((Number(part.amount) / t) * 100)));
}

// ---- user input -------------------------------------------------------------------------------------------

/**
 * Text typed into an amount field -> canonical whole-VND digits for the API ("1.500.000" -> "1500000"),
 * or null when it is empty, zero or not a number. VND has no minor unit, so decimals are not accepted.
 */
export function parseVndInput(text: string): string | null {
  const digits = text.replace(/[\s.,_]/g, "");
  if (!/^\d+$/.test(digits)) return null;
  const canonical = digits.replace(/^0+(?=\d)/, "");
  return canonical === "0" ? null : canonical;
}

/** Digits only, for controlled amount inputs: strips everything else as the user types. */
export function sanitizeDigits(text: string): string {
  return text.replace(/\D/g, "").replace(/^0+(?=\d)/, "");
}
