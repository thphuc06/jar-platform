import { AlertTriangle, ArrowRightLeft, Inbox, Plus } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router-dom";
import { Cell, Pie, PieChart, ResponsiveContainer } from "recharts";
import { useJars } from "@/api/queries";
import MoveMoneyDialog, { type JarOption } from "@/components/MoveMoneyDialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { formatVND } from "@/lib/format";
import { wholeVnd } from "@/lib/money";

const JAR_COLORS = ["hsl(215, 80%, 48%)", "hsl(160, 55%, 38%)", "hsl(270, 55%, 50%)", "hsl(40, 85%, 52%)", "hsl(340, 68%, 50%)", "hsl(190, 65%, 42%)"];

/**
 * The six jars of BR-1 and the unallocated money (the hidden POOL jar). Jars are fixed: unlike the old UI there is no
 * creating, deleting or "editing the allocation" of a jar, because a balance only changes through transactions.
 * Allocating = moving money out of Unallocated.
 */
const BudgetJars = () => {
  const jars = useJars();
  const [move, setMove] = useState<{ from: string; to: string } | null>(null);

  if (jars.isLoading) {
    return (
      <div className="space-y-4 animate-slide-up">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-32 w-full rounded-xl" />
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-16 w-full rounded-xl" />
        ))}
      </div>
    );
  }
  if (!jars.data) {
    return <p role="alert" className="text-sm text-destructive">Could not load your jars: {jars.error?.message}</p>;
  }

  const { poolJarId, unallocated, total } = jars.data;
  const list = jars.data.jars;
  const options: JarOption[] = [
    { id: poolJarId, name: "Unallocated", balance: unallocated },
    ...list.map((j) => ({ id: j.id, name: j.name, balance: j.balance, targetPct: j.targetPct })),
  ];
  const totalVnd = wholeVnd(total);
  // integer percentage of the money that sits in a jar; BigInt keeps it exact, the result is small enough for Number
  const allocatedPct = totalVnd === 0n ? 0 : Number(((totalVnd - wholeVnd(unallocated)) * 100n) / totalVnd);
  const pie = list.filter((j) => wholeVnd(j.balance) > 0n).map((j) => ({ name: j.name, value: Number(j.balance) }));

  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-display font-bold">Budget Jars</h1>
          <p className="text-muted-foreground text-sm">{list.length} jars</p>
        </div>
        <Button
          size="sm"
          className="gap-1.5 gradient-premium border-0 shadow-md shadow-primary/20"
          disabled={wholeVnd(unallocated) === 0n}
          onClick={() => setMove({ from: poolJarId, to: list[0]?.id ?? poolJarId })}
        >
          <Plus className="h-4 w-4" /> Allocate
        </Button>
      </div>

      <Card>
        <CardContent className="p-4 space-y-3">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">Total balance</span>
            <span className="font-bold text-lg">{formatVND(total)}</span>
          </div>
          <Progress value={allocatedPct} className="h-2.5" aria-label="Allocated share" />
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>In jars: {allocatedPct}%</span>
            <span data-testid="unallocated">Unallocated: {formatVND(unallocated)}</span>
          </div>
          {wholeVnd(unallocated) > 0n && (
            <p className="flex items-center gap-1.5 text-xs text-amber-600 dark:text-amber-400">
              <AlertTriangle className="h-3.5 w-3.5" /> {formatVND(unallocated)} is not in a jar yet: allocate it so you can spend it.
            </p>
          )}
        </CardContent>
      </Card>

      {pie.length > 0 && (
        <Card>
          <CardContent className="p-6">
            <h2 className="font-display font-semibold mb-2 text-sm">Money per jar</h2>
            <div className="h-48">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie data={pie} dataKey="value" innerRadius={50} outerRadius={80} paddingAngle={3}>
                    {pie.map((p, i) => (
                      <Cell key={p.name} fill={JAR_COLORS[i % JAR_COLORS.length]} />
                    ))}
                  </Pie>
                </PieChart>
              </ResponsiveContainer>
            </div>
          </CardContent>
        </Card>
      )}

      <div className="space-y-2">
        {list.map((jar, i) => (
          <Card key={jar.id} className="overflow-hidden">
            <div className="h-1" style={{ backgroundColor: JAR_COLORS[i % JAR_COLORS.length] }} />
            <CardContent className="flex items-center justify-between gap-3 p-4">
              <div className="min-w-0">
                <p className="font-semibold text-sm">{jar.name}</p>
                <p className="text-xs text-muted-foreground">Target {jar.targetPct}% of income</p>
                <p className="font-display font-bold">{formatVND(jar.balance)}</p>
              </div>
              <div className="flex shrink-0 gap-1">
                <Button variant="outline" size="sm" className="h-8 gap-1.5 text-xs" aria-label={`Move money out of ${jar.name}`} onClick={() => setMove({ from: jar.id, to: poolJarId })}>
                  <ArrowRightLeft className="h-3.5 w-3.5" /> Move
                </Button>
                <Button asChild variant="ghost" size="sm" className="h-8 w-8 p-0" aria-label={`Transactions of ${jar.name}`}>
                  <Link to={`/inbox?jarId=${jar.id}`}>
                    <Inbox className="h-3.5 w-3.5" />
                  </Link>
                </Button>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      {move && <MoveMoneyDialog options={options} initialFrom={move.from} initialTo={move.to} onClose={() => setMove(null)} />}
    </div>
  );
};

export default BudgetJars;
