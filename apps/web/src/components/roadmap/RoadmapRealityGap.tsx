import { AlertCircle, TrendingDown, TrendingUp } from "lucide-react";
import { formatAmount } from "@/lib/money";
import { cn } from "@/lib/utils";

interface Props {
  requiredMonthly: number;
  currentMonthly: number;
  paceGap: number;
  offTrackPercent: number;
  trend: string;
}

export const RoadmapRealityGap = ({ requiredMonthly, currentMonthly, paceGap, offTrackPercent, trend }: Props) => {
  const isOffTrack = paceGap < 0;
  const TrendIcon = trend === "declining" ? TrendingDown : TrendingUp;

  return (
    <div className={cn("space-y-2 rounded-lg border p-3", isOffTrack ? "border-destructive/20 bg-destructive/5" : "border-success/20 bg-success/5")}>
      <div className="flex items-center gap-2">
        <AlertCircle className={cn("h-4 w-4", isOffTrack ? "text-destructive" : "text-success")} aria-hidden="true" />
        <span className="text-xs font-semibold">Khoảng cách với thực tế</span>
        {isOffTrack && (
          <span className="ml-auto rounded-full bg-destructive/10 px-1.5 py-0.5 text-[10px] font-semibold text-destructive">
            Chậm {offTrackPercent.toFixed(1)}% so với mục tiêu
          </span>
        )}
      </div>

      <div className="space-y-2">
        {[
          { label: "Cần tiết kiệm", value: formatAmount(requiredMonthly), valueClass: "text-foreground" },
          { label: "Hiện đang tiết kiệm", value: formatAmount(currentMonthly), valueClass: currentMonthly < 0 ? "text-destructive" : "text-success" },
          { label: "Chênh lệch", value: formatAmount(paceGap), valueClass: paceGap < 0 ? "text-destructive" : "text-success" },
        ].map((item) => (
          <div key={item.label} className="flex items-center justify-between">
            <span className="text-[10px] text-muted-foreground">{item.label}</span>
            <span className={cn("text-xs font-semibold", item.valueClass)}>
              {item.value}
              <span className="text-[9px] font-normal text-muted-foreground"> /tháng</span>
            </span>
          </div>
        ))}
      </div>

      {isOffTrack && (
        <div className="flex items-center gap-1.5 border-t border-destructive/10 pt-1">
          <TrendIcon className="h-3 w-3 text-destructive" aria-hidden="true" />
          <p className="text-[10px] leading-relaxed text-destructive">
            Với tốc độ hiện tại, số dư của bạn sẽ giảm thay vì tăng: mỗi tháng thiếu {formatAmount(Math.abs(paceGap))} so với mức cần thiết.
          </p>
        </div>
      )}
    </div>
  );
};
