import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { ArrowRight, CheckCircle2, ChevronDown, Clock, Target, Zap } from "lucide-react";
import type { NextActionCard } from "@/api/goals";
import { cn } from "@/lib/utils";

export const RoadmapNextAction = ({ action }: { action: NextActionCard }) => {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="space-y-2 rounded-lg border-2 border-primary/30 bg-primary/5 p-3">
      <div className="flex items-center gap-2">
        <Zap className="h-4 w-4 text-primary" aria-hidden="true" />
        <span className="text-xs font-semibold">Việc nên làm tiếp theo</span>
        <span className="ml-auto flex items-center gap-1 text-[10px] font-medium text-primary">
          <Clock className="h-3 w-3" aria-hidden="true" />
          {action.timeframe}
        </span>
      </div>

      <p className="text-xs font-medium">{action.title}</p>
      <p className="text-[10px] leading-relaxed text-muted-foreground">{action.why}</p>

      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        aria-expanded={expanded}
        className="flex items-center gap-1 rounded text-[10px] text-primary/80 transition-colors hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <span>Chi tiết</span>
        <ChevronDown className={cn("h-3 w-3 transition-transform", expanded && "rotate-180")} aria-hidden="true" />
      </button>

      <AnimatePresence initial={false}>
        {expanded && (
          <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} className="overflow-hidden">
            <div className="space-y-2 rounded-md border border-primary/10 bg-primary/5 p-2">
              <div className="flex items-start gap-1.5">
                <Target className="mt-0.5 h-3 w-3 shrink-0 text-primary" aria-hidden="true" />
                <div>
                  <p className="text-[9px] font-medium uppercase tracking-wider text-muted-foreground">Kết quả kỳ vọng</p>
                  <p className="mt-0.5 text-[10px] leading-relaxed text-foreground">{action.expectedOutcome}</p>
                </div>
              </div>
              <div className="flex items-start gap-1.5">
                <CheckCircle2 className="mt-0.5 h-3 w-3 shrink-0 text-success" aria-hidden="true" />
                <div>
                  <p className="text-[9px] font-medium uppercase tracking-wider text-muted-foreground">Dấu hiệu thành công</p>
                  <p className="mt-0.5 text-[10px] leading-relaxed text-foreground">{action.successSignal}</p>
                </div>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <div className="flex items-center gap-1.5 border-t border-primary/10 pt-1">
        <ArrowRight className="h-3 w-3 text-primary" aria-hidden="true" />
        <p className="text-[10px] text-primary">{action.followOnTransition}</p>
      </div>
    </div>
  );
};
