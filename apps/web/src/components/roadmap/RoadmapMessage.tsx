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
  const { roadmapContract: contract, uiExplanation, explanation } = data;
  const viz = contract.visualizationSupport;
  const [expandedPhase, setExpandedPhase] = useState<string | null>(null);

  const requiredMonthly = (contract.goal.targetAmount - viz.keyMetrics.currentProgressAmount) / contract.goal.targetTimelineMonths;
  const currentMonthly = viz.keyMetrics.currentMonthlyCapacity.amount;
  const paceGap = currentMonthly - requiredMonthly;
  const offTrackPercent = ((contract.goal.targetAmount - viz.projectionChart.projectedEndAmount) / contract.goal.targetAmount) * 100;

  return (
    <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} className="w-full space-y-3">
      <RoadmapStatusBanner banner={viz.statusBanner} />
      <RoadmapGoalHeader goal={contract.goal} progress={viz.keyMetrics.currentProgressRatio} />
      <RoadmapRealityGap
        requiredMonthly={requiredMonthly}
        currentMonthly={currentMonthly}
        paceGap={paceGap}
        offTrackPercent={offTrackPercent}
        trend={viz.projectionChart.trend}
      />
      <RoadmapKeyMetrics metrics={viz.keyMetrics} plannerState={contract.plannerState} />
      <RoadmapTimeline
        nodes={viz.timelineNodes}
        milestones={contract.milestones}
        expandedPhase={expandedPhase}
        onTogglePhase={(phase) => setExpandedPhase(expandedPhase === phase ? null : phase)}
        phaseCards={viz.phaseCards}
        unlockConditions={viz.unlockConditions}
        whyCurrentPhase={uiExplanation.whyCurrentPhase}
      />
      <RoadmapProjectionChart chart={viz.projectionChart} projectionExplanation={explanation.projectionExplanation} />
      <RoadmapConfidence label={viz.projectionChart.confidenceLabel} note={explanation.confidenceNote} cautions={explanation.cautions} />
      <RoadmapNextAction action={viz.nextActionCard} />

      <div className="space-y-2 rounded-lg bg-muted/50 p-3">
        <p className="text-[11px] font-medium text-muted-foreground">Vì sao có lộ trình này</p>
        <p className="text-xs leading-relaxed text-muted-foreground">{uiExplanation.whyThisRoadmap}</p>
        {uiExplanation.whatHasToChangeNext && (
          <div className="border-t border-border pt-1.5">
            <p className="text-[11px] font-medium text-muted-foreground">Cần thay đổi điều gì</p>
            <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">{uiExplanation.whatHasToChangeNext}</p>
          </div>
        )}
      </div>
    </motion.div>
  );
};

export default RoadmapMessage;
