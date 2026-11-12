import { AlertTriangle, Info, ShieldCheck, type LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { levelLabel } from "./labels";

interface Props {
  label: string;
  note: string;
  cautions: string[];
}

const labelConfig: Record<string, { icon: LucideIcon; color: string }> = {
  cautious: { icon: AlertTriangle, color: "text-warning" },
  low: { icon: AlertTriangle, color: "text-destructive" },
  moderate: { icon: Info, color: "text-info" },
  high: { icon: ShieldCheck, color: "text-success" },
};

export const RoadmapConfidence = ({ label, note, cautions }: Props) => {
  const config = labelConfig[label] || labelConfig.cautious;
  const Icon = config.icon;

  return (
    <div className="space-y-2 rounded-lg bg-muted/50 p-3">
      <div className="flex items-center gap-1.5">
        <Icon className={cn("h-3.5 w-3.5", config.color)} aria-hidden="true" />
        <span className={cn("text-[10px] font-semibold uppercase tracking-wider", config.color)}>Độ tin cậy {levelLabel(label)}</span>
      </div>
      <p className="text-[10px] leading-relaxed text-muted-foreground">{note}</p>
      {cautions.length > 0 && (
        <ul className="space-y-0.5">
          {cautions.slice(0, 3).map((c) => (
            <li key={c} className="flex gap-1 text-[9px] text-muted-foreground">
              <span className="shrink-0">•</span>
              <span>{c}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};
