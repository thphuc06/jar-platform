const vnd = new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND", maximumFractionDigits: 0 });

/**
 * Formats money as sent by the API: a decimal STRING such as "85000.00". Intl.NumberFormat (ES2023) formats a numeric
 * string exactly; converting it to a JS number first would round anything above 2^53 ("…567" would print "…568").
 * Numbers are accepted for UI-only values such as quick-amount buttons.
 */
export function formatVND(amount: string | number): string {
  return vnd.format(typeof amount === "string" ? (amount as `${number}`) : amount);
}
