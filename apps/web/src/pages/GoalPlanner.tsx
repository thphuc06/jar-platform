import { useMemo, useState } from "react";
import { motion } from "framer-motion";
import { CheckCircle2, Plus, Sparkles, Target, Trash2 } from "lucide-react";
import { CartesianGrid, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { useDailyBalances } from "@/api/dashboard";
import { useArchiveGoal, useGoalFeasibility, useGoals, useUpdateGoal, type Goal } from "@/api/goals";
import { useJars } from "@/api/jars";
import { describeError } from "@/api/problem";
import ErrorNotice from "@/components/ErrorNotice";
import NewGoalDialog from "@/components/goals/NewGoalDialog";
import WhatIfPanel from "@/components/goals/WhatIfPanel";
import GoalRoadmap from "@/components/roadmap/GoalRoadmap";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/hooks/use-toast";
import { formatDate, monthsBetween } from "@/lib/dates";
import { formatAmount, formatCompact, formatMoney, moneyToNumber, percentOf } from "@/lib/money";
import { cn } from "@/lib/utils";

const container = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.08 } },
};
const item = {
  hidden: { opacity: 0, y: 12 },
  show: { opacity: 1, y: 0 },
};

const GoalCard = ({ goal, selected, progress, onSelect }: { goal: Goal; selected: boolean; progress: number | null; onSelect: () => void }) => {
  const { toast } = useToast();
  const feasibility = useGoalFeasibility(goal.id);
  const update = useUpdateGoal();
  const archive = useArchiveGoal();
  const achieved = goal.status === "ACHIEVED";

  const fail = (title: string) => (error: unknown) => toast({ title, description: describeError(error), variant: "destructive" });

  return (
    <Card className={cn("overflow-hidden transition-all duration-300 hover:shadow-lg hover:shadow-primary/5", selected && "ring-2 ring-primary")}>
      <CardContent className="p-0">
        <button
          type="button"
          onClick={onSelect}
          aria-pressed={selected}
          className="block w-full p-5 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <div className="mb-3 flex items-center gap-3">
            <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-primary/10 text-primary">
              {achieved ? <CheckCircle2 className="h-5 w-5 text-success" aria-hidden="true" /> : <Target className="h-5 w-5" aria-hidden="true" />}
            </div>
            <div className="min-w-0 flex-1">
              <h3 className="font-semibold">
                {goal.name}
                {achieved && <span className="ml-2 text-xs font-normal text-success">Đã hoàn thành</span>}
              </h3>
              <p className="text-xs text-muted-foreground">
                Mục tiêu: {formatMoney(goal.targetAmount)} • đến {formatDate(goal.targetDate)} (còn {monthsBetween(new Date(), goal.targetDate)} tháng)
              </p>
            </div>
          </div>
          {progress !== null && <Progress value={progress} className="mb-2 h-1.5" aria-label={`Tiến độ của mục tiêu ${goal.name}`} />}
          <div className="text-xs text-muted-foreground">
            {feasibility.data ? (
              <>
                Cần tiết kiệm ~{formatMoney(feasibility.data.requiredMonthly)}/tháng •{" "}
                <span className={verdictView(feasibility.data.verdict).className}>{verdictView(feasibility.data.verdict).label}</span>
                {typeof feasibility.data.probabilityOfSuccess === "number" && <> • khoảng {Math.round(feasibility.data.probabilityOfSuccess * 100)}% khả năng đạt</>}
              </>
            ) : feasibility.isPending ? (
              "Đang tính mức cần tiết kiệm..."
            ) : (
              "Chưa tính được mức cần tiết kiệm"
            )}
          </div>
        </button>
        <div className="flex justify-end gap-1 border-t px-3 py-1.5">
          {!achieved && (
            <Button
              variant="ghost"
              size="sm"
              className="h-8 gap-1.5 text-xs"
              disabled={update.isPending}
              onClick={() =>
                update.mutate(
                  { id: goal.id, status: "ACHIEVED" },
                  { onSuccess: () => toast({ title: "Chúc mừng bạn!", description: `Đã hoàn thành mục tiêu "${goal.name}"` }), onError: fail("Không cập nhật được mục tiêu") },
                )
              }
            >
              <CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" /> Đã hoàn thành
            </Button>
          )}
          <Button
            variant="ghost"
            size="sm"
            className="h-8 gap-1.5 text-xs text-muted-foreground hover:text-destructive"
            disabled={archive.isPending}
            onClick={() =>
              archive.mutate(goal.id, {
                onSuccess: () => toast({ title: "Đã lưu trữ mục tiêu", description: goal.name }),
                onError: fail("Không lưu trữ được mục tiêu"),
              })
            }
          >
            <Trash2 className="h-3.5 w-3.5" aria-hidden="true" /> Lưu trữ
            <span className="sr-only">mục tiêu {goal.name}</span>
          </Button>
        </div>
      </CardContent>
    </Card>
  );
};

/** What the server's `verdict` of a feasibility check means on screen. */
const verdictView = (verdict: string): { label: string; className: string } => {
  switch (verdict) {
    case "ACHIEVED":
      return { label: "đã đủ tiền", className: "text-success" };
    case "ON_TRACK":
      return { label: "đang đúng tiến độ", className: "text-success" };
    case "OFF_TRACK":
      return { label: "chưa kịp tiến độ", className: "text-destructive" };
    default:
      return { label: "chưa đủ dữ liệu để đánh giá", className: "text-muted-foreground" };
  }
};

const BalanceVsTarget = ({ goal }: { goal: Goal }) => {
  const balances = useDailyBalances(365);

  // last balance of every month
  const data = useMemo(() => {
    const byMonth = new Map<string, number>();
    for (const b of balances.data ?? []) byMonth.set(b.date.slice(0, 7), moneyToNumber(b.balance));
    return [...byMonth.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([month, balance]) => ({ month, balance }));
  }, [balances.data]);

  if (data.length < 2) return null;
  const target = moneyToNumber(goal.targetAmount);
  const remaining = Math.max(0, target - (data[data.length - 1]?.balance ?? 0));

  return (
    <Card>
      <CardContent className="p-5">
        <div className="mb-1 flex items-center gap-2">
          <Sparkles className="h-4 w-4 text-primary" aria-hidden="true" />
          <h3 className="font-display font-semibold">Số dư so với mục tiêu</h3>
        </div>
        <p className="mb-4 text-xs text-muted-foreground">
          {goal.name} — {formatMoney(goal.targetAmount)}
        </p>
        <div className="h-56" role="img" aria-label="Biểu đồ số dư theo tháng so với số tiền mục tiêu">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data} margin={{ top: 5, right: 10, left: -10, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
              <XAxis dataKey="month" tick={{ fontSize: 11 }} stroke="hsl(var(--muted-foreground))" />
              <YAxis
                tick={{ fontSize: 10 }}
                stroke="hsl(var(--muted-foreground))"
                tickFormatter={(v) => formatCompact(Number(v))}
                domain={[0, (dataMax: number) => Math.max(dataMax, target) * 1.1]}
              />
              <Tooltip formatter={(value) => formatAmount(Number(value ?? 0))} labelFormatter={(label) => `Tháng ${label}`} />
              <Legend wrapperStyle={{ fontSize: 11 }} />
              <ReferenceLine y={target} stroke="hsl(var(--destructive))" strokeDasharray="6 4" strokeWidth={2} label={{ value: "Mục tiêu", position: "right", fontSize: 10, fill: "hsl(var(--destructive))" }} />
              <Line type="monotone" dataKey="balance" stroke="hsl(var(--primary))" strokeWidth={2.5} dot={{ r: 3 }} activeDot={{ r: 5 }} name="Số dư" />
            </LineChart>
          </ResponsiveContainer>
        </div>
        <p className="mt-2 text-center text-xs text-muted-foreground">Còn thiếu: {formatAmount(remaining)}</p>
      </CardContent>
    </Card>
  );
};

const GoalPlanner = () => {
  const goals = useGoals();
  const jars = useJars();
  const [showDialog, setShowDialog] = useState(false);
  const [selectedGoalId, setSelectedGoalId] = useState<string | null>(null);

  const goalList = useMemo(() => (goals.data ?? []).filter((g) => g.status !== "ARCHIVED"), [goals.data]);
  const activeGoal = goalList.find((g) => g.id === selectedGoalId) ?? goalList[0];

  const progressOf = (goal: Goal): number | null => {
    const jar = jars.data?.find((j) => j.id === goal.fundingJarId);
    return jar ? percentOf(jar.balance, goal.targetAmount) : null;
  };

  if (goals.isPending) {
    return (
      <div className="animate-slide-up space-y-4">
        <Skeleton className="h-8 w-48" />
        {Array.from({ length: 3 }).map((_, i) => (
          <Skeleton key={i} className="h-28 w-full rounded-xl" />
        ))}
      </div>
    );
  }

  return (
    <motion.div variants={container} initial="hidden" animate="show" className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-display font-bold">Mục tiêu</h1>
          <p className="text-sm text-muted-foreground">{goalList.length} mục tiêu</p>
        </div>
        <Button size="sm" className="gap-1.5 border-0 shadow-md shadow-primary/20 gradient-premium" onClick={() => setShowDialog(true)}>
          <Plus className="h-4 w-4" aria-hidden="true" /> Mục tiêu mới
        </Button>
      </div>

      {goals.isError && <ErrorNotice error={goals.error} what="danh sách mục tiêu" onRetry={() => void goals.refetch()} />}

      <div className="space-y-3">
        {goalList.map((goal) => (
          <motion.div key={goal.id} variants={item}>
            <GoalCard goal={goal} selected={activeGoal?.id === goal.id} progress={progressOf(goal)} onSelect={() => setSelectedGoalId(goal.id)} />
          </motion.div>
        ))}
        {!goals.isError && goalList.length === 0 && (
          <Card>
            <CardContent className="p-8 text-center text-sm text-muted-foreground">Chưa có mục tiêu nào. Bấm "Mục tiêu mới" để tạo một mục tiêu!</CardContent>
          </Card>
        )}
      </div>

      {activeGoal && (
        <>
          <motion.div variants={item}>
            <BalanceVsTarget goal={activeGoal} />
          </motion.div>

          <motion.div variants={item}>
            <WhatIfPanel key={activeGoal.id} goal={activeGoal} />
          </motion.div>

          <motion.div variants={item} className="space-y-3">
            <div>
              <h2 className="text-lg font-display font-semibold">Lộ trình</h2>
              <p className="text-sm text-muted-foreground">Các giai đoạn để đạt mục tiêu "{activeGoal.name}"</p>
            </div>
            <GoalRoadmap key={activeGoal.id} goalId={activeGoal.id} />
          </motion.div>
        </>
      )}

      <NewGoalDialog open={showDialog} onClose={() => setShowDialog(false)} />
    </motion.div>
  );
};

export default GoalPlanner;
