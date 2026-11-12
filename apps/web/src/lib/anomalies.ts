import type { Anomaly, AnomalyFeedback, AnomalyStatus } from "@/api/types";

export const ANOMALY_STATUS_LABEL: Record<AnomalyStatus, string> = {
  OPEN: "Đang mở",
  CONFIRMED: "Đã xác nhận",
  EXPECTED: "Đã dự kiến",
  FALSE_POSITIVE: "Báo nhầm",
};

/** The verdict buttons, with the meaning that the detector learns from. */
export const FEEDBACK_OPTIONS: Array<{ label: AnomalyFeedback; text: string }> = [
  { label: "CONFIRMED", text: "Đúng, bất thường" },
  { label: "EXPECTED", text: "Tôi đã dự kiến" },
  { label: "FALSE_POSITIVE", text: "Báo nhầm" },
];

export const SEVERITY_LABEL: Record<string, string> = {
  HIGH: "Cao",
  MEDIUM: "Trung bình",
  LOW: "Thấp",
};

/** One line that says what was detected: MAD flags a single outlier, CUSUM a drift of the spending level. */
export function anomalyHeadline(anomaly: Pick<Anomaly, "detector" | "direction" | "jarName">): string {
  const jar = anomaly.jarName ? ` ở hũ ${anomaly.jarName}` : "";
  if (anomaly.detector === "MAD") return `Khoản chi đột biến${jar}`;
  return anomaly.direction === "DOWN" ? `Chi tiêu giảm kéo dài${jar}` : `Chi tiêu tăng kéo dài${jar}`;
}
