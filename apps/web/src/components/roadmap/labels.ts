/** Vietnamese labels for the codes in the roadmap payload; unknown codes are shown humanized, never dropped. */

const GOAL_TYPE: Record<string, string> = {
  buy_home: "Mua nhà",
  home_purchase: "Mua nhà",
  emergency_fund: "Quỹ khẩn cấp",
  investment: "Đầu tư",
  vacation: "Du lịch",
  education: "Giáo dục",
};

const LEVEL: Record<string, string> = {
  low: "thấp",
  moderate: "trung bình",
  high: "cao",
  critical: "rất cao",
  cautious: "thận trọng",
};

/** "home_purchase_phase" -> "Home purchase phase" */
export function humanize(code: string): string {
  const text = code.split("_").join(" ").trim();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function goalTypeLabel(type: string): string {
  return GOAL_TYPE[type] ?? humanize(type);
}

/** low / moderate / high / critical / cautious -> thấp / trung bình / cao / rất cao / thận trọng */
export function levelLabel(code: string): string {
  return LEVEL[code] ?? code;
}
