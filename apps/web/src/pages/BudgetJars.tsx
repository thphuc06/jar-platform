import { useMemo, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { AlertTriangle, ArrowRightLeft, Archive, ChevronDown, Coins, Loader2, PencilLine, Plus, Send } from "lucide-react";
import { CartesianGrid, Cell, Legend, Line, LineChart, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { useBudgetPeriod, type JarPeriod } from "@/api/budget";
import { useDashboard } from "@/api/dashboard";
import { useArchiveJar, useJars, useUpdateJar } from "@/api/jars";
import { describeError } from "@/api/problem";
import type { Jar, JarSpend } from "@/api/types";
import ErrorNotice from "@/components/ErrorNotice";
import AllocateIncomeDialog from "@/components/jars/AllocateIncomeDialog";
import NewJarDialog from "@/components/jars/NewJarDialog";
import PayJarDialog from "@/components/jars/PayJarDialog";
import TransferJarDialog from "@/components/jars/TransferJarDialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/hooks/use-toast";
import { monthKey } from "@/lib/dates";
import { jarColor } from "@/lib/jars";
import { formatAmount, formatCompact, formatMoney, isZero, moneyToNumber, percentOf, sumMoney } from "@/lib/money";
import { formatPct, parsePct, sumPct } from "@/lib/percent";
import { cn } from "@/lib/utils";

interface JarCardProps {
  jar: Jar;
  index: number;
  open: boolean;
  spend: JarSpend | undefined;
  plan: JarPeriod | undefined;
  usedPct: number;
  onToggle: () => void;
  onPay: () => void;
  onTransfer: () => void;
  onArchive: () => void;
}

const JarCard = ({ jar, index, open, spend, plan, usedPct, onToggle, onPay, onTransfer, onArchive }: JarCardProps) => {
  const { toast } = useToast();
  const update = useUpdateJar();
  const [editing, setEditing] = useState(false);
  const [pctText, setPctText] = useState("");

  const pct = parsePct(pctText);
  const overLimit = pct !== null && sumPct([usedPct, -jar.targetPct, pct]) > 100;
  const color = jarColor(jar, index);
  const empty = isZero(jar.balance);

  const savePct = (event: React.FormEvent) => {
    event.preventDefault();
    if (pct === null || overLimit) return;
    update.mutate(
      { id: jar.id, targetPct: pct },
      {
        onSuccess: () => {
          toast({ title: "Đã cập nhật tỷ lệ", description: `${jar.name}: ${formatPct(pct)}` });
          setEditing(false);
        },
        onError: (error) => toast({ title: "Không cập nhật được tỷ lệ", description: describeError(error), variant: "destructive" }),
      },
    );
  };

  const panelId = `jar-panel-${jar.id}`;

  return (
    <Card className="overflow-hidden transition-shadow duration-200 hover:shadow-md">
      <div className="h-1" style={{ backgroundColor: color }} />
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        aria-controls={panelId}
        className="flex w-full items-center justify-between px-4 py-3 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <div className="flex items-center gap-3">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg text-xs font-bold text-primary-foreground" style={{ backgroundColor: color }} aria-hidden="true">
            {jar.name.charAt(0)}
          </div>
          <div>
            <span className="text-sm font-semibold">{jar.name}</span>
            <p className="text-xs text-muted-foreground">
              Số dư {formatMoney(jar.balance)} · {formatPct(jar.targetPct)} thu nhập
            </p>
          </div>
        </div>
        <ChevronDown className={cn("h-4 w-4 text-muted-foreground transition-transform duration-200", open && "rotate-180")} aria-hidden="true" />
      </button>

      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            id={panelId}
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden"
          >
            <div className="space-y-3 px-4 pb-4">
              <div className="grid grid-cols-3 gap-2 text-xs">
                <div>
                  <p className="text-muted-foreground">Số dư</p>
                  <p className="font-semibold">{formatMoney(jar.balance)}</p>
                </div>
                <div>
                  <p className="text-muted-foreground">Đã chi tháng này</p>
                  <p className="font-semibold text-destructive">{spend ? formatMoney(spend.spent) : "-"}</p>
                </div>
                <div>
                  <p className="text-muted-foreground">Tỷ lệ mục tiêu</p>
                  <p className="font-semibold">{formatPct(jar.targetPct)}</p>
                </div>
              </div>

              {plan && (
                <div className="space-y-1">
                  <div className="flex justify-between text-xs text-muted-foreground">
                    <span>Kế hoạch tháng: {formatMoney(plan.planned)}</span>
                    <span>Đã chi: {formatMoney(plan.spent)}</span>
                  </div>
                  <Progress value={percentOf(plan.spent, plan.planned)} className="h-2" aria-label={`Đã chi so với kế hoạch của hũ ${jar.name}`} />
                </div>
              )}

              {editing ? (
                <form onSubmit={savePct} className="space-y-2" noValidate>
                  <Label htmlFor={`pct-${jar.id}`} className="text-xs">
                    Tỷ lệ thu nhập (%)
                  </Label>
                  <div className="flex items-center gap-2">
                    <Input id={`pct-${jar.id}`} inputMode="decimal" value={pctText} onChange={(e) => setPctText(e.target.value)} className="h-8 text-sm" autoFocus aria-invalid={pct === null || overLimit ? true : undefined} />
                    <Button type="submit" size="sm" className="h-8 text-xs" disabled={pct === null || overLimit || update.isPending}>
                      {update.isPending ? <Loader2 className="h-3 w-3 animate-spin" aria-label="Đang lưu" /> : "Lưu"}
                    </Button>
                    <Button type="button" variant="ghost" size="sm" className="h-8 text-xs" onClick={() => setEditing(false)}>
                      Hủy
                    </Button>
                  </div>
                  {(pct === null || overLimit) && (
                    <p role="alert" className="text-xs text-destructive">
                      {pct === null ? "Nhập số từ 0 đến 100 (tối đa 2 chữ số thập phân)." : `Tổng tỷ lệ các hũ không được vượt quá 100% (tối đa ${formatPct(sumPct([100, -usedPct, jar.targetPct]))} cho hũ này).`}
                    </p>
                  )}
                </form>
              ) : (
                <Button
                  variant="outline"
                  size="sm"
                  className="h-8 w-full gap-1.5 text-xs"
                  onClick={() => {
                    setPctText(String(jar.targetPct).replace(".", ","));
                    setEditing(true);
                  }}
                >
                  <PencilLine className="h-3.5 w-3.5" aria-hidden="true" /> Sửa tỷ lệ
                </Button>
              )}

              <div className="flex gap-2">
                <Button variant="outline" size="sm" className="h-8 flex-1 gap-1.5 text-xs" onClick={onPay}>
                  <Send className="h-3.5 w-3.5" aria-hidden="true" /> Chi tiêu
                </Button>
                <Button variant="outline" size="sm" className="h-8 flex-1 gap-1.5 text-xs" onClick={onTransfer}>
                  <ArrowRightLeft className="h-3.5 w-3.5" aria-hidden="true" /> Chuyển hũ
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-8 w-8 p-0 text-muted-foreground hover:text-destructive"
                  onClick={onArchive}
                  disabled={!empty}
                  title={empty ? undefined : "Chỉ lưu trữ được hũ có số dư bằng 0"}
                >
                  <Archive className="h-3.5 w-3.5" aria-hidden="true" />
                  <span className="sr-only">{empty ? `Lưu trữ hũ ${jar.name}` : "Chỉ lưu trữ được hũ có số dư bằng 0"}</span>
                </Button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </Card>
  );
};

const BudgetJars = () => {
  const { toast } = useToast();
  const jars = useJars();
  const dashboard = useDashboard();
  const month = useMemo(() => monthKey(), []);
  const budget = useBudgetPeriod(month);
  const archive = useArchiveJar();

  const [expanded, setExpanded] = useState<string | null>(null);
  const [payJar, setPayJar] = useState<Jar | null>(null);
  const [transferJar, setTransferJar] = useState<Jar | null>(null);
  const [archiveJar, setArchiveJar] = useState<Jar | null>(null);
  const [showNewJar, setShowNewJar] = useState(false);
  const [showAllocate, setShowAllocate] = useState(false);

  const jarList = useMemo(() => jars.data ?? [], [jars.data]);
  const totalBalance = useMemo(() => sumMoney(jarList.map((j) => j.balance)), [jarList]);
  const usedPct = useMemo(() => sumPct(jarList.map((j) => j.targetPct)), [jarList]);

  const pieData = jarList
    .map((jar, i) => ({ name: jar.name, value: moneyToNumber(jar.balance), color: jarColor(jar, i) }))
    .filter((d) => d.value > 0);
  const trend = (dashboard.data?.trend ?? []).map((m) => ({
    month: m.month,
    income: moneyToNumber(m.income),
    expense: moneyToNumber(m.expense),
  }));

  const confirmArchive = () => {
    if (!archiveJar) return;
    archive.mutate(archiveJar.id, {
      onSuccess: () => {
        toast({ title: "Đã lưu trữ hũ", description: archiveJar.name });
        setArchiveJar(null);
      },
      onError: (error) => toast({ title: "Không lưu trữ được hũ", description: describeError(error), variant: "destructive" }),
    });
  };

  if (jars.isPending) {
    return (
      <div className="animate-slide-up space-y-4">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-32 w-full rounded-xl" />
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-14 w-full rounded-xl" />
        ))}
      </div>
    );
  }
  if (jars.isError) return <ErrorNotice error={jars.error} what="danh sách hũ" onRetry={() => void jars.refetch()} />;

  return (
    <div className="animate-slide-up space-y-6">
      <div className="flex items-center justify-between gap-2">
        <div>
          <h1 className="text-2xl font-display font-bold">Hũ ngân sách</h1>
          <p className="text-sm text-muted-foreground">{jarList.length} hũ</p>
        </div>
        <div className="flex gap-2">
          <Button size="sm" variant="outline" className="gap-1.5" onClick={() => setShowAllocate(true)}>
            <Coins className="h-4 w-4" aria-hidden="true" /> Phân bổ thu nhập
          </Button>
          <Button size="sm" className="gap-1.5 border-0 shadow-md shadow-primary/20 gradient-premium" onClick={() => setShowNewJar(true)}>
            <Plus className="h-4 w-4" aria-hidden="true" /> Hũ mới
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="space-y-3 p-4">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">Tổng số dư các hũ</span>
            <span className="text-lg font-bold">{formatMoney(totalBalance)}</span>
          </div>
          <Progress value={Math.min(100, usedPct)} className="h-2.5" aria-label="Tổng tỷ lệ đã phân bổ cho các hũ" />
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>Tỷ lệ đã phân bổ: {formatPct(usedPct)}</span>
            <span>Còn lại: {formatPct(sumPct([100, -usedPct]))}</span>
          </div>
          {usedPct !== 100 && (
            <div className="flex items-center gap-1.5 text-xs text-amber-600 dark:text-amber-400">
              <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              <span>
                {usedPct > 100
                  ? "Tổng tỷ lệ các hũ vượt 100%. Hãy giảm tỷ lệ của một số hũ."
                  : "Tổng tỷ lệ chưa đủ 100%. Khi phân bổ thu nhập, các tỷ lệ phải đúng 100%."}
              </span>
            </div>
          )}
        </CardContent>
      </Card>

      {pieData.length > 0 && (
        <Card>
          <CardContent className="p-6">
            <h2 className="mb-2 font-display text-sm font-semibold">Số dư theo hũ</h2>
            <div className="h-48" role="img" aria-label="Biểu đồ tròn số dư của từng hũ">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie data={pieData} cx="50%" cy="50%" innerRadius={50} outerRadius={80} paddingAngle={3} dataKey="value" nameKey="name">
                    {pieData.map((d) => (
                      <Cell key={d.name} fill={d.color} />
                    ))}
                  </Pie>
                  <Tooltip formatter={(value) => formatAmount(Number(value ?? 0))} />
                </PieChart>
              </ResponsiveContainer>
            </div>
            <ul className="mt-2 flex flex-wrap justify-center gap-x-3 gap-y-1">
              {pieData.map((d) => (
                <li key={d.name} className="flex items-center gap-1.5 text-xs">
                  <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: d.color }} aria-hidden="true" />
                  <span className="text-muted-foreground">
                    {d.name}: {formatAmount(d.value)}
                  </span>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}

      {trend.length > 1 && (
        <Card>
          <CardContent className="p-6">
            <h2 className="mb-2 font-display text-sm font-semibold">Thu chi 6 tháng gần nhất</h2>
            <div className="h-64" role="img" aria-label="Biểu đồ đường thu và chi theo tháng">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={trend} margin={{ top: 5, right: 10, left: -10, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
                  <XAxis dataKey="month" tick={{ fontSize: 11 }} />
                  <YAxis tick={{ fontSize: 11 }} tickFormatter={(v) => formatCompact(Number(v))} />
                  <Tooltip formatter={(value) => formatAmount(Number(value ?? 0))} labelFormatter={(label) => `Tháng ${label}`} />
                  <Legend wrapperStyle={{ fontSize: 11 }} />
                  <Line type="monotone" dataKey="income" name="Thu" stroke="hsl(var(--success))" strokeWidth={2} dot={{ r: 3 }} activeDot={{ r: 5 }} />
                  <Line type="monotone" dataKey="expense" name="Chi" stroke="hsl(var(--destructive))" strokeWidth={2} dot={{ r: 3 }} activeDot={{ r: 5 }} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </CardContent>
        </Card>
      )}

      {budget.data && budget.data.jars.length > 0 && (
        <Card>
          <CardContent className="space-y-3 p-5">
            <div className="flex items-baseline justify-between">
              <h2 className="font-display text-sm font-semibold">Kế hoạch tháng {budget.data.month}</h2>
              <span className="text-xs text-muted-foreground">
                Đã chi {formatMoney(budget.data.spent)} / kế hoạch {formatMoney(budget.data.planned)}
                {budget.data.closed && " · đã khép sổ"}
              </span>
            </div>
            {budget.data.jars.map((line) => (
              <div key={line.jarId} className="space-y-1">
                <div className="flex justify-between text-xs">
                  <span className="font-medium">{line.name}</span>
                  <span className="text-muted-foreground">
                    {formatMoney(line.spent)} / {formatMoney(line.planned)}
                  </span>
                </div>
                <Progress value={percentOf(line.spent, line.planned)} className="h-1.5" aria-label={`Đã chi so với kế hoạch của hũ ${line.name}`} />
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      <div className="space-y-2">
        {jarList.map((jar, i) => (
          <JarCard
            key={jar.id}
            jar={jar}
            index={i}
            open={expanded === jar.id}
            spend={dashboard.data?.spendByJar.find((s) => s.jarId === jar.id)}
            plan={budget.data?.jars.find((l) => l.jarId === jar.id)}
            usedPct={usedPct}
            onToggle={() => setExpanded(expanded === jar.id ? null : jar.id)}
            onPay={() => setPayJar(jar)}
            onTransfer={() => setTransferJar(jar)}
            onArchive={() => setArchiveJar(jar)}
          />
        ))}
      </div>

      <NewJarDialog open={showNewJar} usedPct={usedPct} onClose={() => setShowNewJar(false)} />
      <AllocateIncomeDialog open={showAllocate} jars={jarList} onClose={() => setShowAllocate(false)} />
      <PayJarDialog jar={payJar} onClose={() => setPayJar(null)} />
      <TransferJarDialog from={transferJar} jars={jarList} onClose={() => setTransferJar(null)} />

      <Dialog open={archiveJar !== null} onOpenChange={(open) => !open && setArchiveJar(null)}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle className="font-display">Lưu trữ hũ {archiveJar?.name}?</DialogTitle>
            <DialogDescription>Hũ sẽ được ẩn khỏi danh sách. Lịch sử giao dịch của hũ vẫn được giữ lại.</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setArchiveJar(null)} disabled={archive.isPending}>
              Hủy
            </Button>
            <Button variant="destructive" onClick={confirmArchive} disabled={archive.isPending} className="gap-1.5">
              {archive.isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
              Lưu trữ
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
};

export default BudgetJars;
