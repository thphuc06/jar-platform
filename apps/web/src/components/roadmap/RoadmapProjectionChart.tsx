import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { ChevronDown, Info } from "lucide-react";
import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { ProjectionChart, ProjectionExplanation } from "@/api/goals";
import { formatAmount, formatCompact } from "@/lib/money";
import { cn } from "@/lib/utils";

interface Props {
  chart: ProjectionChart;
  projectionExplanation?: ProjectionExplanation;
}

export const RoadmapProjectionChart = ({ chart, projectionExplanation }: Props) => {
  const [showExplanation, setShowExplanation] = useState(false);

  const data = chart.series.map((p) => ({
    month: p.month,
    projected: p.projectedAmount,
    target: chart.targetAmount,
  }));

  return (
    <div className="space-y-2 rounded-lg border bg-card p-3">
      <div className="flex items-center justify-between">
        <p className="text-xs font-semibold">Dự phóng</p>
        <span className="rounded bg-warning/10 px-1.5 py-0.5 text-[9px] font-medium text-warning">{chart.trend === "declining" ? "Đang giảm" : "Đang tăng"}</span>
      </div>

      <div role="img" aria-label="Biểu đồ dự phóng số dư theo tháng so với mục tiêu">
        <ResponsiveContainer width="100%" height={160}>
          <LineChart data={data} margin={{ top: 5, right: 5, left: -20, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
            <XAxis dataKey="month" tick={{ fontSize: 9 }} tickFormatter={(m) => `T${m}`} />
            <YAxis tick={{ fontSize: 9 }} tickFormatter={(v) => formatCompact(Number(v))} />
            <Tooltip formatter={(value) => formatAmount(Number(value ?? 0))} labelFormatter={(label) => `Tháng ${label}`} contentStyle={{ fontSize: 11, borderRadius: 8 }} />
            <ReferenceLine y={chart.targetAmount} stroke="hsl(var(--success))" strokeDasharray="4 4" label={{ value: "Mục tiêu", fontSize: 9, fill: "hsl(var(--success))" }} />
            <Line type="monotone" dataKey="projected" stroke="hsl(var(--primary))" strokeWidth={2} dot={{ r: 3, fill: "hsl(var(--primary))" }} name="Dự phóng" />
          </LineChart>
        </ResponsiveContainer>
      </div>

      <div className="flex justify-between text-[10px] text-muted-foreground">
        <span>Còn thiếu: {formatAmount(chart.gapToTarget)}</span>
        <span>Cuối kỳ: {formatAmount(chart.projectedEndAmount)}</span>
      </div>

      {projectionExplanation && (
        <>
          <button
            type="button"
            onClick={() => setShowExplanation(!showExplanation)}
            aria-expanded={showExplanation}
            className="flex items-center gap-1 rounded text-[10px] text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <Info className="h-3 w-3" aria-hidden="true" />
            <span>Vì sao có dự phóng này</span>
            <ChevronDown className={cn("h-3 w-3 transition-transform", showExplanation && "rotate-180")} aria-hidden="true" />
          </button>

          <AnimatePresence initial={false}>
            {showExplanation && (
              <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }} className="overflow-hidden">
                <div className="space-y-1.5 rounded-md bg-muted/50 p-2.5">
                  {[
                    { label: "Cơ sở", text: projectionExplanation.basis },
                    { label: "Tốc độ", text: projectionExplanation.paceAssessment },
                    { label: "Khoảng cách", text: projectionExplanation.targetGapCommentary },
                    { label: "Xu hướng", text: projectionExplanation.trendCommentary },
                  ].map((item) => (
                    <div key={item.label}>
                      <p className="text-[9px] font-semibold uppercase tracking-wider text-muted-foreground">{item.label}</p>
                      <p className="text-[10px] leading-relaxed text-foreground">{item.text}</p>
                    </div>
                  ))}
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </>
      )}
    </div>
  );
};
