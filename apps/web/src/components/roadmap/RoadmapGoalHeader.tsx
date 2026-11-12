import { Target } from "lucide-react";
import type { RoadmapGoal } from "@/api/goals";
import { Progress } from "@/components/ui/progress";
import { formatAmount } from "@/lib/money";
import { goalTypeLabel } from "./labels";

export const RoadmapGoalHeader = ({ goal, progress }: { goal: RoadmapGoal; progress: number }) => {
  const progressPercent = Math.round(progress * 100 * 100) / 100;

  return (
    <div className="space-y-2 rounded-lg border bg-card p-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Target className="h-4 w-4 text-primary" aria-hidden="true" />
          <span className="text-sm font-semibold">{goalTypeLabel(goal.goal_type)}</span>
        </div>
        <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">{goal.target_timeline_months} tháng</span>
      </div>
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-lg font-display font-bold">{formatAmount(goal.target_amount)}</span>
        <span className="text-xs text-muted-foreground">đã đạt {progressPercent}%</span>
      </div>
      <Progress value={progressPercent} className="h-1.5" aria-label="Tiến độ mục tiêu" />
    </div>
  );
};
