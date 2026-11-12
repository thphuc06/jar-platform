import { AlertTriangle, Info, Shield, type LucideIcon } from "lucide-react";
import type { StatusBanner } from "@/api/goals";
import { cn } from "@/lib/utils";

const toneConfig: Record<string, { icon: LucideIcon; bg: string; border: string; text: string }> = {
  cautious: { icon: AlertTriangle, bg: "bg-warning/10", border: "border-warning/30", text: "text-warning" },
  critical: { icon: AlertTriangle, bg: "bg-destructive/10", border: "border-destructive/30", text: "text-destructive" },
  stable: { icon: Shield, bg: "bg-success/10", border: "border-success/30", text: "text-success" },
  default: { icon: Info, bg: "bg-info/10", border: "border-info/30", text: "text-info" },
};

export const RoadmapStatusBanner = ({ banner }: { banner: StatusBanner }) => {
  const config = toneConfig[banner.tone] || toneConfig.default;
  const Icon = config.icon;

  return (
    <div className={cn("flex items-start gap-2.5 rounded-lg border p-3", config.bg, config.border)}>
      <Icon className={cn("mt-0.5 h-4 w-4 shrink-0", config.text)} aria-hidden="true" />
      <div className="min-w-0">
        <p className={cn("text-xs font-semibold", config.text)}>{banner.title}</p>
        <p className="mt-0.5 text-[11px] leading-relaxed text-muted-foreground">{banner.message}</p>
      </div>
    </div>
  );
};
