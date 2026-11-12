import { useState } from "react";
import { motion } from "framer-motion";
import type { RoadmapResponse } from "@/api/goals";
import { RoadmapConfidence } from "./RoadmapConfidence";
import { RoadmapGoalHeader } from "./RoadmapGoalHeader";
import { RoadmapKeyMetrics } from "./RoadmapKeyMetrics";
import { RoadmapNextAction } from "./RoadmapNextAction";
import { RoadmapProjectionChart } from "./RoadmapProjectionChart";
import { RoadmapRealityGap } from "./RoadmapRealityGap";
import { RoadmapStatusBanner } from "./RoadmapStatusBanner";
import { RoadmapTimeline } from "./RoadmapTimeline";

/** The roadmap of a goal (GET /goals/{id}/roadmap), laid out as the cards of the hackathon UI. */
const RoadmapMessage = ({ data }: { data: RoadmapResponse }) => {
  const { roadmap_contract: contract, ui_explanation, explanation } = data;
  const viz = contract.visualization_support;
  const [expandedPhase, setExpandedPhase] = useState<string | null>(null);

  const requiredMonthly = (contract.goal.target_amount - viz.key_metrics.current_progress_amount) / contract.goal.target_timeline_months;
  const currentMonthly = viz.key_metrics.current_monthly_capacity.amount;
  const paceGap = currentMonthly - requiredMonthly;
  const offTrackPercent = ((contract.goal.target_amount - viz.projection_chart.projected_end_amount) / contract.goal.target_amount) * 100;

  return (
    <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} className="w-full space-y-3">
      <RoadmapStatusBanner banner={viz.status_banner} />
      <RoadmapGoalHeader goal={contract.goal} progress={viz.key_metrics.current_progress_ratio} />
      <RoadmapRealityGap
        requiredMonthly={requiredMonthly}
        currentMonthly={currentMonthly}
        paceGap={paceGap}
        offTrackPercent={offTrackPercent}
        trend={viz.projection_chart.trend}
      />
      <RoadmapKeyMetrics metrics={viz.key_metrics} plannerState={contract.planner_state} />
      <RoadmapTimeline
        nodes={viz.timeline_nodes}
        milestones={contract.milestones}
        expandedPhase={expandedPhase}
        onTogglePhase={(phase) => setExpandedPhase(expandedPhase === phase ? null : phase)}
        phaseCards={viz.phase_cards}
        unlockConditions={viz.unlock_conditions}
        whyCurrentPhase={ui_explanation.why_current_phase}
      />
      <RoadmapProjectionChart chart={viz.projection_chart} projectionExplanation={explanation.projection_explanation} />
      <RoadmapConfidence label={viz.projection_chart.confidence_label} note={explanation.confidence_note} cautions={explanation.cautions} />
      <RoadmapNextAction action={viz.next_action_card} />

      <div className="space-y-2 rounded-lg bg-muted/50 p-3">
        <p className="text-[11px] font-medium text-muted-foreground">Vì sao có lộ trình này</p>
        <p className="text-xs leading-relaxed text-muted-foreground">{ui_explanation.why_this_roadmap}</p>
        {ui_explanation.what_has_to_change_next && (
          <div className="border-t border-border pt-1.5">
            <p className="text-[11px] font-medium text-muted-foreground">Cần thay đổi điều gì</p>
            <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">{ui_explanation.what_has_to_change_next}</p>
          </div>
        )}
      </div>
    </motion.div>
  );
};

export default RoadmapMessage;
