import { AlertTriangle, Clock3, Sparkles, Target, Wrench } from "lucide-react";
import { useGoalRoadmap, type RoadmapResponse } from "@/api/goals";
import { describeError } from "@/api/problem";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { formatAmount } from "@/lib/money";
import { cn } from "@/lib/utils";
import { goalTypeLabel, humanize, levelLabel } from "./labels";
import RoadmapMessage from "./RoadmapMessage";

const toneStyles: Record<string, string> = {
  cautious: "bg-warning/10 text-warning border-warning/30",
  low: "bg-success/10 text-success border-success/30",
  moderate: "bg-info/10 text-info border-info/30",
  high: "bg-destructive/10 text-destructive border-destructive/30",
};

const Overview = ({ data }: { data: RoadmapResponse }) => {
  const { result, roadmap_contract: contract, ui_explanation, explanation } = data;
  const viz = contract.visualization_support;
  const service = explanation.next_action_service_explanation;
  const monthlyNeeded = Math.round((contract.goal.target_amount - viz.key_metrics.current_progress_amount) / contract.goal.target_timeline_months);
  const toneClass = toneStyles[viz.status_banner.tone] || "bg-primary/10 text-primary border-primary/20";

  return (
    <>
      <Card className="overflow-hidden border-primary/60 shadow-lg shadow-primary/10">
        <CardContent className="p-5 sm:p-6">
          <div className="flex items-start gap-4">
            <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl bg-primary/10 text-primary">
              <Target className="h-7 w-7" aria-hidden="true" />
            </div>
            <div className="min-w-0 flex-1 space-y-3">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0">
                  <p className="text-xs font-semibold uppercase tracking-[0.16em] text-primary/70">Lộ trình</p>
                  <h3 className="mt-1 text-xl font-display font-bold">{goalTypeLabel(contract.goal.goal_type)}</h3>
                  <p className="text-sm text-muted-foreground">
                    {formatAmount(contract.goal.target_amount)} • {contract.goal.target_timeline_months} tháng
                  </p>
                </div>
                <Badge variant="outline" className="w-fit border-primary/20 bg-primary/5 text-primary">
                  {humanize(result.current_phase)}
                </Badge>
              </div>

              <p className="text-sm leading-6 text-foreground/85">{result.summary}</p>

              <div className="grid gap-2 sm:grid-cols-3">
                <div className="rounded-xl border bg-muted/30 p-3">
                  <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Nhịp tiết kiệm</p>
                  <p className="mt-1 text-lg font-semibold">{formatAmount(monthlyNeeded)}</p>
                  <p className="text-xs text-muted-foreground">mỗi tháng để đúng hạn</p>
                </div>
                <div className="rounded-xl border bg-muted/30 p-3">
                  <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Mức sẵn sàng</p>
                  <p className="mt-1 text-lg font-semibold capitalize">{contract.planner_state.readiness_label}</p>
                  <p className="text-xs text-muted-foreground">Khả năng tiết kiệm {formatAmount(contract.planner_state.savings_capacity.amount_monthly)}/tháng</p>
                </div>
                <div className="rounded-xl border bg-muted/30 p-3">
                  <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Chi tiêu bất thường</p>
                  <p className="mt-1 text-lg font-semibold capitalize">{contract.planner_state.anomaly_state}</p>
                  <p className="text-xs text-muted-foreground">{ui_explanation.what_has_to_change_next}</p>
                </div>
              </div>
            </div>
          </div>
        </CardContent>
      </Card>

      <div className={cn("grid gap-4", service && "lg:grid-cols-[1.3fr_0.7fr]")}>
        <Card className="border-border/80">
          <CardContent className="p-5">
            <div className="flex items-start gap-3">
              <div className={cn("mt-0.5 rounded-full border px-2.5 py-1 text-xs font-semibold", toneClass)}>{levelLabel(viz.status_banner.tone).toUpperCase()}</div>
              <div className="min-w-0">
                <p className="text-sm font-semibold text-foreground">{viz.status_banner.title}</p>
                <p className="mt-1 text-sm leading-6 text-muted-foreground">{ui_explanation.why_this_roadmap}</p>
              </div>
            </div>
          </CardContent>
        </Card>

        {service && (
          <Card className="border-border/80">
            <CardContent className="p-5">
              <div className="flex items-center gap-2 text-sm font-semibold">
                <Sparkles className="h-4 w-4 text-primary" aria-hidden="true" />
                Dịch vụ phù hợp
              </div>
              <div className="mt-4 space-y-3">
                <div className="rounded-xl border border-primary/20 bg-primary/5 p-3">
                  <div className="flex items-center gap-2">
                    <Wrench className="h-4 w-4 text-primary" aria-hidden="true" />
                    <p className="font-semibold">{service.selected_service.display_name_vi}</p>
                  </div>
                  <p className="mt-2 text-sm leading-6 text-muted-foreground">{service.explanation}</p>
                </div>
                {service.selected_service.why_this_service_now && (
                  <div className="rounded-xl border bg-muted/30 p-3">
                    <div className="flex items-center gap-2 text-sm font-semibold">
                      <Clock3 className="h-4 w-4 text-primary" aria-hidden="true" />
                      Vì sao là lúc này
                    </div>
                    <p className="mt-2 text-sm leading-6 text-muted-foreground">{service.selected_service.why_this_service_now}</p>
                  </div>
                )}
              </div>
            </CardContent>
          </Card>
        )}
      </div>
    </>
  );
};

/** The roadmap of one goal: summary, phases, projection and the next action. */
const GoalRoadmap = ({ goalId }: { goalId: string }) => {
  const roadmap = useGoalRoadmap(goalId);

  if (roadmap.isPending) return <Skeleton className="h-48 w-full rounded-xl" />;
  if (roadmap.isError) {
    return (
      <Card>
        <CardContent className="p-6 text-center text-sm text-muted-foreground">
          <p>Chưa có lộ trình cho mục tiêu này.</p>
          <p className="mt-1 text-xs">{describeError(roadmap.error)}</p>
        </CardContent>
      </Card>
    );
  }

  const maturityEvents = roadmap.data.roadmap_contract.maturity_events ?? [];

  return (
    <div className="space-y-4">
      <Overview data={roadmap.data} />
      <RoadmapMessage data={roadmap.data} />
      {maturityEvents.length > 0 && (
        <Card className="border-border/80">
          <CardContent className="p-5">
            <div className="flex items-center gap-2 text-sm font-semibold">
              <AlertTriangle className="h-4 w-4 text-warning" aria-hidden="true" />
              Các mốc sắp tới
            </div>
            <ul className="mt-4 grid gap-3">
              {maturityEvents.map((event) => (
                <li key={event.event_type} className="rounded-xl border bg-muted/30 p-4">
                  <p className="font-semibold">{event.title}</p>
                  <p className="mt-1 text-sm leading-6 text-muted-foreground">Điều kiện: {event.condition}</p>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}
    </div>
  );
};

export default GoalRoadmap;
