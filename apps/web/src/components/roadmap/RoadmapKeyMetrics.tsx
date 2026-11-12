import { Activity, BarChart3, Clock, Gauge, Shield, Wallet, type LucideIcon } from "lucide-react";
import type { KeyMetrics, PlannerState } from "@/api/goals";
import { formatAmount } from "@/lib/money";
import { cn } from "@/lib/utils";
import { levelLabel } from "./labels";

interface Props {
  metrics: KeyMetrics;
  plannerState?: PlannerState;
}

interface Metric {
  icon: LucideIcon;
  label: string;
  value: string;
  sub: string;
  valueClass?: string;
}

const feasibilityColors: Record<string, string> = {
  low: "text-destructive",
  moderate: "text-warning",
  high: "text-success",
};

const riskColors: Record<string, string> = {
  low: "text-success",
  moderate: "text-warning",
  high: "text-destructive",
  critical: "text-destructive",
};

const MetricGrid = ({ items }: { items: Metric[] }) => (
  <div className="grid grid-cols-2 gap-2">
    {items.map((item) => (
      <div key={item.label} className="space-y-1 rounded-lg border bg-card p-2.5">
        <div className="flex items-center gap-1.5">
          <item.icon className="h-3 w-3 text-muted-foreground" aria-hidden="true" />
          <span className="text-[10px] font-medium text-muted-foreground">{item.label}</span>
        </div>
        <p className={cn("truncate text-xs font-semibold", item.valueClass)}>{item.value}</p>
        <p className="truncate text-[10px] text-muted-foreground">{item.sub}</p>
      </div>
    ))}
  </div>
);

export const RoadmapKeyMetrics = ({ metrics, plannerState }: Props) => {
  const items: Metric[] = [
    {
      icon: Wallet,
      label: "Tiến độ",
      value: formatAmount(metrics.currentProgressAmount),
      sub: `${(metrics.currentProgressRatio * 100).toFixed(1)}% mục tiêu`,
    },
    {
      icon: Activity,
      label: "Khả năng tiết kiệm",
      value: formatAmount(metrics.currentMonthlyCapacity.amount),
      sub: metrics.currentMonthlyCapacity.label === "none" ? "Chưa có khả năng tiết kiệm" : metrics.currentMonthlyCapacity.label,
      valueClass: metrics.currentMonthlyCapacity.amount < 0 ? "text-destructive" : "text-success",
    },
    {
      icon: Clock,
      label: "Thời hạn",
      value: `${metrics.timelineMonths} tháng`,
      sub: plannerState ? `Quỹ dự phòng: ${plannerState.runwayMonths.toFixed(0)} tháng` : `Dự kiến: ${formatAmount(metrics.projectedEndAmount)}`,
    },
    {
      icon: BarChart3,
      label: "Tính khả thi",
      value: levelLabel(metrics.feasibilityHint).toUpperCase(),
      sub: `Mức sẵn sàng: ${metrics.readinessLabel}`,
      valueClass: feasibilityColors[metrics.feasibilityHint],
    },
  ];

  const signals: Metric[] = plannerState
    ? [
        {
          icon: Shield,
          label: "Quỹ đệm",
          value: plannerState.bufferStatus === "present" ? "Có" : "Chưa có",
          sub: `Áp lực thanh khoản: ${levelLabel(plannerState.liquidityPressure)}`,
          valueClass: plannerState.bufferStatus === "present" ? "text-success" : "text-destructive",
        },
        {
          icon: Gauge,
          label: "Rủi ro",
          value: levelLabel(plannerState.riskBand).toUpperCase(),
          sub: plannerState.anomalyState === "active" ? "Đang có chi tiêu bất thường" : "Không có bất thường",
          valueClass: riskColors[plannerState.riskBand],
        },
      ]
    : [];

  return (
    <div className="space-y-2">
      <MetricGrid items={items} />
      {signals.length > 0 && <MetricGrid items={signals} />}
    </div>
  );
};
