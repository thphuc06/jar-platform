import { useState } from "react";
import { Calculator, Loader2 } from "lucide-react";
import { useGoalScenario, type Goal, type GoalScenarios } from "@/api/goals";
import { describeError } from "@/api/problem";
import AmountField from "@/components/AmountField";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { formatMoney, parseVndInput } from "@/lib/money";

const percent = (fraction: number) => `${Math.round(fraction * 100)}%`;

/** The answer: the Monte Carlo odds of reaching the target with the extra saving, against keeping things as they are. */
const Outcome = ({ data }: { data: GoalScenarios }) => {
  const custom = data.scenarios[0];
  if (!custom) return <p className="mt-3 text-xs text-muted-foreground">Chưa có kết quả cho mức này.</p>;
  const before = data.base.monteCarlo.probabilityOfSuccess;
  const after = custom.monteCarlo.probabilityOfSuccess;
  const better = after >= before;
  return (
    <div className="mt-4 rounded-lg border bg-muted/30 p-3 text-sm">
      <p className={better ? "font-semibold text-success" : "font-semibold text-destructive"}>
        Khả năng đạt mục tiêu: {percent(after)} (giữ nguyên: {percent(before)})
      </p>
      <p className="mt-1 text-xs text-muted-foreground">
        Sau {data.horizonMonths} tháng, số tiền ở mức trung vị khoảng {formatMoney(custom.monteCarlo.p50)}, mục tiêu {formatMoney(data.targetAmount)}.
        So với giữ nguyên, thêm khoảng {formatMoney(custom.medianVsBase)}.
      </p>
      {data.notes.map((note) => (
        <p key={note} className="mt-1 text-xs text-muted-foreground">
          {note}
        </p>
      ))}
    </div>
  );
};

/** "What if I save this much more every month?" (POST /goals/{id}/scenarios). Nothing is saved. */
const WhatIfPanel = ({ goal }: { goal: Goal }) => {
  const scenario = useGoalScenario();
  const [extra, setExtra] = useState("");
  const value = parseVndInput(extra);

  const run = (event: React.FormEvent) => {
    event.preventDefault();
    if (value) scenario.mutate({ id: goal.id, monthlySavingDelta: value });
  };

  return (
    <Card>
      <CardContent className="p-5">
        <div className="mb-1 flex items-center gap-2">
          <Calculator className="h-4 w-4 text-primary" aria-hidden="true" />
          <h3 className="font-display font-semibold">Thử kịch bản</h3>
        </div>
        <p className="mb-4 text-xs text-muted-foreground">Nếu mỗi tháng bạn tiết kiệm thêm một khoản, mục tiêu "{goal.name}" thay đổi thế nào?</p>
        <form onSubmit={run} className="space-y-3" noValidate>
          <AmountField id="whatif-extra" label="Tiết kiệm thêm mỗi tháng (VND)" value={extra} onChange={setExtra} quick={[500_000, 1_000_000, 2_000_000]} />
          <Button type="submit" size="sm" className="gap-1.5" disabled={!value || scenario.isPending}>
            {scenario.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />}
            Tính thử
          </Button>
        </form>
        <div role="status" aria-live="polite">
          {scenario.isError && <p className="mt-3 text-xs text-destructive">{describeError(scenario.error)}</p>}
          {scenario.data && <Outcome data={scenario.data} />}
        </div>
      </CardContent>
    </Card>
  );
};

export default WhatIfPanel;
