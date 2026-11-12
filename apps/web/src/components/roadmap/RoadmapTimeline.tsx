import { AnimatePresence, motion } from "framer-motion";
import { CheckCircle2, ChevronDown, Circle, Loader2, Lock, Unlock } from "lucide-react";
import type { MilestoneData, PhaseData, TimelineNode, UnlockCondition } from "@/api/goals";
import { cn } from "@/lib/utils";

interface Props {
  nodes: TimelineNode[];
  milestones: MilestoneData[];
  expandedPhase: string | null;
  onTogglePhase: (phase: string) => void;
  phaseCards?: PhaseData[];
  unlockConditions?: UnlockCondition[];
  whyCurrentPhase?: string;
}

const StatusIcon = ({ status }: { status: string }) => {
  if (status === "completed") return <CheckCircle2 className="h-4 w-4 text-success" aria-label="Đã hoàn thành" />;
  if (status === "current") return <Loader2 className="h-4 w-4 animate-spin text-primary" aria-label="Đang thực hiện" />;
  return <Circle className="h-4 w-4 text-muted-foreground/40" aria-label="Sắp tới" />;
};

const ConditionList = ({ title, items, bullet, bulletClass }: { title: string; items: string[]; bullet: string; bulletClass?: string }) =>
  items.length > 0 ? (
    <div>
      <p className="text-[9px] font-semibold uppercase tracking-wider text-muted-foreground">{title}</p>
      <ul className="mt-0.5 space-y-0.5">
        {items.map((item) => (
          <li key={item} className="flex gap-1 text-[10px] text-muted-foreground">
            <span className={cn("shrink-0", bulletClass)}>{bullet}</span>
            <span>{item}</span>
          </li>
        ))}
      </ul>
    </div>
  ) : null;

export const RoadmapTimeline = ({ nodes, milestones, expandedPhase, onTogglePhase, phaseCards, unlockConditions, whyCurrentPhase }: Props) => (
  <div className="rounded-lg border bg-card p-3">
    <p className="mb-3 text-xs font-semibold">Lộ trình theo giai đoạn</p>
    <div className="relative">
      {nodes.map((node, i) => {
        const milestone = milestones.find((m) => m.phaseType === node.phaseType);
        const phaseCard = phaseCards?.find((p) => p.phaseType === node.phaseType);
        const unlock = unlockConditions?.find((u) => u.phaseType === node.phaseType);
        const isExpanded = expandedPhase === node.phaseType;
        const isLast = i === nodes.length - 1;
        const isCurrent = node.status === "current";
        const detailsId = `phase-${node.phaseType}`;

        return (
          <div key={node.phaseType} className="relative flex gap-3">
            {!isLast && (
              <div className={cn("absolute bottom-0 left-[7px] top-5 w-0.5", node.status === "completed" ? "bg-success" : isCurrent ? "bg-primary/30" : "bg-border")} />
            )}

            <div className="relative z-10 mt-0.5 shrink-0">
              <StatusIcon status={node.status} />
            </div>

            <div className={cn("flex-1 pb-4", isLast && "pb-0")}>
              <button
                type="button"
                onClick={() => onTogglePhase(node.phaseType)}
                aria-expanded={isExpanded}
                aria-controls={detailsId}
                className="flex w-full items-center gap-1 rounded text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <span className={cn("text-xs font-medium", isCurrent ? "text-primary" : node.status === "completed" ? "text-success" : "text-muted-foreground")}>{node.title}</span>
                <ChevronDown className={cn("ml-auto h-3 w-3 text-muted-foreground/50 transition-transform", isExpanded && "rotate-180")} aria-hidden="true" />
              </button>

              {isCurrent && !isExpanded && (
                <div className="mt-1 space-y-0.5">
                  <p className="text-[10px] leading-relaxed text-muted-foreground">{node.objective}</p>
                  {whyCurrentPhase && <p className="text-[10px] italic leading-relaxed text-primary/70">{whyCurrentPhase}</p>}
                </div>
              )}

              <AnimatePresence initial={false}>
                {isExpanded && (
                  <motion.div id={detailsId} initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} className="overflow-hidden">
                    <div className="mt-2 space-y-2 rounded-md bg-muted/50 p-2.5">
                      <p className="text-[10px] leading-relaxed text-muted-foreground">{node.objective}</p>

                      {isCurrent && whyCurrentPhase && (
                        <div className="rounded border border-primary/10 bg-primary/5 p-2">
                          <p className="mb-0.5 text-[9px] font-semibold uppercase tracking-wider text-primary">Vì sao bạn ở giai đoạn này</p>
                          <p className="text-[10px] leading-relaxed text-foreground">{whyCurrentPhase}</p>
                        </div>
                      )}

                      {phaseCard && (
                        <div className="space-y-1.5">
                          <ConditionList title="Điều kiện bắt đầu" items={phaseCard.entryConditions} bullet="→" bulletClass="text-primary" />
                          <ConditionList title="Điều kiện hoàn thành" items={phaseCard.exitConditions} bullet="✓" bulletClass="text-success" />
                          <ConditionList title="Kết quả kỳ vọng" items={phaseCard.expectedResults} bullet="•" />
                        </div>
                      )}

                      {unlock && unlock.conditions.length > 0 && (
                        <div className="rounded border border-border bg-muted p-2">
                          <div className="mb-1 flex items-center gap-1">
                            {unlock.status === "current" ? <Unlock className="h-3 w-3 text-primary" aria-hidden="true" /> : <Lock className="h-3 w-3 text-muted-foreground" aria-hidden="true" />}
                            <p className="text-[9px] font-semibold uppercase tracking-wider text-muted-foreground">Điều kiện mở khóa</p>
                          </div>
                          <ul className="space-y-0.5">
                            {unlock.conditions.map((c) => (
                              <li key={c} className="flex gap-1 text-[10px] text-muted-foreground">
                                <span className="shrink-0">•</span>
                                <span>{c}</span>
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}

                      {milestone && (
                        <div className="border-t border-border pt-1.5">
                          <p className="text-[10px] font-medium">Cột mốc: {milestone.title}</p>
                          <p className="mt-0.5 text-[9px] text-muted-foreground">Mở khóa khi: {milestone.unlockRule}</p>
                          <p className="text-[9px] text-muted-foreground">Kết quả: {milestone.expectedResult}</p>
                        </div>
                      )}
                    </div>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          </div>
        );
      })}
    </div>
  </div>
);
