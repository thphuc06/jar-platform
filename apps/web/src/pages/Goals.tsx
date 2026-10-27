import { CheckCircle2, Loader2, Pencil, Plus, RotateCcw, Target, Trash2 } from "lucide-react";
import { useState } from "react";
import { useArchiveGoal, useCreateGoal, useGoals, useJars, useUpdateGoal, type GoalChanges, type NewGoal } from "@/api/queries";
import type { components } from "@/api";
import AmountField from "@/components/AmountField";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/hooks/use-toast";
import { formatLocalDate, formatVND } from "@/lib/format";
import { parseAmount } from "@/lib/money";

type Goal = components["schemas"]["GoalResponse"];

const selectClass =
  "flex h-10 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring";

/** Create (goal undefined) or edit a goal. Editing sends only what changed (merge patch). */
const GoalDialog = ({ goal, onClose }: { goal?: Goal; onClose: () => void }) => {
  const { toast } = useToast();
  const jars = useJars();
  const create = useCreateGoal();
  const update = useUpdateGoal();
  const [name, setName] = useState(goal?.name ?? "");
  const [amountText, setAmountText] = useState(goal ? goal.targetAmount.split(".")[0] : "");
  const [targetDate, setTargetDate] = useState(goal?.targetDate ?? "");
  const [jarId, setJarId] = useState(goal?.fundingJar?.id ?? "");

  const amount = parseAmount(amountText);
  const valid = name.trim() !== "" && amount !== null && targetDate !== "";
  const pending = create.isPending || update.isPending;
  // the server decides with Vietnam's calendar whether the date is in the future (target-date-not-in-future)
  const fail = (err: Error) => toast({ title: "Not saved", description: err.message, variant: "destructive" });
  const saved = (title: string, description?: string) => {
    toast({ title, description });
    onClose();
  };

  const save = () => {
    if (!valid || !amount) return;
    if (!goal) {
      const body: NewGoal = { name: name.trim(), targetAmount: amount, targetDate, fundingJarId: jarId || null };
      create.mutate(body, { onSuccess: () => saved("Goal created", body.name), onError: fail });
      return;
    }
    const changes: GoalChanges = {};
    if (name.trim() !== goal.name) changes.name = name.trim();
    if (amount !== goal.targetAmount.split(".")[0]) changes.targetAmount = amount;
    if (targetDate !== goal.targetDate) changes.targetDate = targetDate;
    if (jarId !== (goal.fundingJar?.id ?? "")) changes.fundingJarId = jarId || null; // null detaches the jar
    if (Object.keys(changes).length === 0) return onClose();
    update.mutate({ id: goal.id, changes }, { onSuccess: () => saved("Goal updated"), onError: fail });
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 font-display">
            <Target className="h-5 w-5 text-primary" /> {goal ? "Edit goal" : "New goal"}
          </DialogTitle>
          <DialogDescription>Progress is the balance of the jar that funds it.</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-2">
          <label className="block space-y-2 text-sm font-medium">
            <span>Name</span>
            <Input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} placeholder="e.g. New laptop" />
          </label>
          <AmountField label="Target (VND)" value={amountText} onChange={setAmountText} quick={[10_000_000, 30_000_000, 100_000_000]} />
          <label className="block space-y-2 text-sm font-medium">
            <span>Target date</span>
            <Input type="date" value={targetDate} onChange={(e) => setTargetDate(e.target.value)} />
          </label>
          <label className="block space-y-2 text-sm font-medium">
            <span>Funding jar</span>
            <select className={selectClass} value={jarId} onChange={(e) => setJarId(e.target.value)}>
              <option value="">None yet</option>
              {(jars.data?.jars ?? []).map((j) => (
                <option key={j.id} value={j.id}>
                  {j.name}
                </option>
              ))}
            </select>
          </label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={save} disabled={!valid || pending} className="gap-1.5">
            {pending && <Loader2 className="h-4 w-4 animate-spin" />} Save
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

/** BR-6 savings goals: nearest deadline first; progress = funding jar balance / target, rounded down, at most 100 %. */
const Goals = () => {
  const { toast } = useToast();
  const goals = useGoals();
  const update = useUpdateGoal();
  const archive = useArchiveGoal();
  const [editing, setEditing] = useState<Goal | "new" | null>(null);

  const setStatus = (goal: Goal, status: "ACTIVE" | "ACHIEVED") =>
    update.mutate(
      { id: goal.id, changes: { status } },
      { onError: (err) => toast({ title: "Not saved", description: err.message, variant: "destructive" }) },
    );
  const remove = (goal: Goal) => {
    if (!window.confirm(`Archive "${goal.name}"? It disappears from this list; your money stays where it is.`)) return;
    archive.mutate(goal.id, {
      onSuccess: () => toast({ title: "Goal archived", description: goal.name }),
      onError: (err) => toast({ title: "Not archived", description: err.message, variant: "destructive" }),
    });
  };

  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-display font-bold">Goals</h1>
          <p className="text-muted-foreground text-sm">Save towards something, one jar at a time</p>
        </div>
        <Button size="sm" className="gap-1.5 gradient-premium border-0 shadow-md shadow-primary/20" onClick={() => setEditing("new")}>
          <Plus className="h-4 w-4" /> New goal
        </Button>
      </div>

      {goals.isLoading && Array.from({ length: 2 }).map((_, i) => <Skeleton key={i} className="h-28 w-full rounded-xl" />)}
      {goals.isError && <p role="alert" className="text-sm text-destructive">Could not load your goals: {goals.error.message}</p>}
      {goals.data?.items.length === 0 && (
        <Card>
          <CardContent className="p-8 text-center text-sm text-muted-foreground">No goals yet. Create one to track your savings.</CardContent>
        </Card>
      )}

      {goals.data?.items.map((goal) => (
        <Card key={goal.id}>
          <CardContent className="space-y-3 p-5">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <h2 className="truncate font-semibold">{goal.name}</h2>
                <p className="text-xs text-muted-foreground">
                  {formatVND(goal.targetAmount)} by {formatLocalDate(goal.targetDate)}
                </p>
              </div>
              {goal.status === "ACHIEVED" && <Badge className="bg-success/10 text-success" variant="secondary">Achieved</Badge>}
            </div>
            {goal.fundingJar ? (
              <>
                <Progress value={goal.progressPct ?? 0} className="h-2" aria-label={`Progress of ${goal.name}`} />
                <p className="text-xs text-muted-foreground">
                  {goal.progressPct}% · {goal.fundingJar.name} holds {formatVND(goal.fundingJar.balance)}
                  {goal.fundingJar.archived && " (archived jar)"}
                </p>
              </>
            ) : (
              <p className="text-xs text-muted-foreground">No funding jar yet: edit the goal to pick one.</p>
            )}
            <div className="flex gap-2">
              <Button variant="outline" size="sm" className="h-8 gap-1.5 text-xs" onClick={() => setEditing(goal)}>
                <Pencil className="h-3.5 w-3.5" /> Edit
              </Button>
              {goal.status === "ACTIVE" ? (
                <Button variant="outline" size="sm" className="h-8 gap-1.5 text-xs" onClick={() => setStatus(goal, "ACHIEVED")}>
                  <CheckCircle2 className="h-3.5 w-3.5" /> Mark achieved
                </Button>
              ) : (
                <Button variant="outline" size="sm" className="h-8 gap-1.5 text-xs" onClick={() => setStatus(goal, "ACTIVE")}>
                  <RotateCcw className="h-3.5 w-3.5" /> Reopen
                </Button>
              )}
              <Button variant="ghost" size="sm" className="h-8 w-8 p-0 text-muted-foreground hover:text-destructive" aria-label={`Archive ${goal.name}`} onClick={() => remove(goal)}>
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
            </div>
          </CardContent>
        </Card>
      ))}

      {editing && <GoalDialog goal={editing === "new" ? undefined : editing} onClose={() => setEditing(null)} />}
    </div>
  );
};

export default Goals;
