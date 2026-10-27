import { ArrowDownRight, ArrowRight, ArrowUpRight, ChevronLeft, ChevronRight, Inbox, PiggyBank, Send, Shield } from "lucide-react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { Area, AreaChart, Bar, BarChart, Cell, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis } from "recharts";
import { useDailyBalances, useDashboard, useRecentTransactions } from "@/api/queries";
import TransactionRow from "@/components/TransactionRow";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { currentBusinessMonth, formatLocalDate, formatMonth, formatMonthShort, formatVND, shiftMonth } from "@/lib/format";

const CHART_COLORS = [
  "hsl(215, 80%, 48%)", "hsl(160, 55%, 38%)", "hsl(270, 55%, 50%)", "hsl(40, 85%, 52%)",
  "hsl(340, 68%, 50%)", "hsl(190, 65%, 42%)", "hsl(30, 70%, 50%)", "hsl(10, 70%, 55%)",
];

// charts need numbers for geometry only; every amount the user READS is formatted from the API's string
const toChart = (amount: string) => Number(amount);

const quickActions = [
  { icon: Send, label: "Spend", to: "/transfer" },
  { icon: PiggyBank, label: "Jars", to: "/jars" },
  { icon: Inbox, label: "Inbox", to: "/inbox" },
];

/** One request for the month (GET /dashboard), plus the 30-day balance curve and the five latest transactions. */
const Dashboard = () => {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const thisMonth = currentBusinessMonth();
  const month = params.get("month") ?? thisMonth;
  // no ?month= → let the server pick the current month; the key "current" is then shared with other visits
  const dashboard = useDashboard(params.get("month") ?? undefined);
  const daily = useDailyBalances(30);
  const recent = useRecentTransactions(5);

  const goTo = (target: string) =>
    setParams(target === thisMonth ? {} : { month: target }, { replace: true });

  const d = dashboard.data;
  const spending = (d?.byCategory ?? []).map((c) => ({ ...c, name: c.name ?? "Uncategorized" }));

  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-display font-bold">Overview</h1>
          <p className="text-sm text-muted-foreground">{formatMonth(month)}</p>
        </div>
        <div className="flex gap-1">
          <Button variant="ghost" size="icon" aria-label="Previous month" onClick={() => goTo(shiftMonth(month, -1))}>
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label="Next month"
            disabled={month >= thisMonth}
            onClick={() => goTo(shiftMonth(month, 1))}
          >
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      </div>

      {dashboard.isError && (
        <p role="alert" className="text-sm text-destructive">Could not load the dashboard: {dashboard.error.message}</p>
      )}

      <Card className="gradient-premium text-primary-foreground overflow-hidden relative shadow-xl shadow-primary/20">
        <CardContent className="p-6 relative z-10 space-y-3">
          <div className="flex items-center gap-2">
            <Shield className="h-4 w-4 text-primary-foreground/70" />
            <p className="text-primary-foreground/70 text-sm font-medium">Total balance</p>
          </div>
          {d ? (
            <>
              <p className="text-3xl font-display font-bold" data-testid="total-balance">{formatVND(d.balances.total)}</p>
              <p className="text-xs text-primary-foreground/80">
                In jars {formatVND(d.balances.allocated)} · Unallocated {formatVND(d.balances.unallocated)}
              </p>
              <div className="flex items-center gap-4 text-sm">
                <span className="flex items-center gap-1">
                  <ArrowDownRight className="h-3 w-3" /> +{formatVND(d.income)}
                </span>
                <span className="flex items-center gap-1">
                  <ArrowUpRight className="h-3 w-3" /> -{formatVND(d.expense)}
                </span>
              </div>
            </>
          ) : (
            <Skeleton className="h-9 w-48 bg-primary-foreground/20" />
          )}
          {daily.data && daily.data.items.length > 1 && (
            <div className="h-14 -mx-2" aria-label="Balance over the last 30 days">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={daily.data.items.map((p) => ({ date: p.date, balance: toChart(p.balance), label: p.balance }))}>
                  <Area type="monotone" dataKey="balance" stroke="currentColor" fill="currentColor" fillOpacity={0.15} strokeWidth={2} />
                  <Tooltip
                    formatter={(_v, _n, item) => formatVND(item.payload.label)}
                    labelFormatter={(_l, items) => (items[0] ? formatLocalDate(items[0].payload.date) : "")}
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          )}
        </CardContent>
        <div className="absolute -right-8 -top-8 h-36 w-36 rounded-full bg-primary-foreground/10 blur-sm" />
      </Card>

      <div className="grid grid-cols-3 gap-3">
        {quickActions.map((a) => (
          <button key={a.label} type="button" className="flex flex-col items-center gap-2 group" onClick={() => navigate(a.to)}>
            <div className="h-12 w-12 rounded-2xl gradient-premium flex items-center justify-center shadow-md group-hover:scale-105 transition-transform">
              <a.icon className="h-5 w-5 text-primary-foreground" />
            </div>
            <span className="text-xs font-medium text-muted-foreground">{a.label}</span>
          </button>
        ))}
      </div>

      <Card>
        <CardContent className="p-5">
          <h2 className="font-display font-semibold mb-3">Spending by category</h2>
          {d && spending.length === 0 && <p className="text-sm text-muted-foreground">No spending in {formatMonth(month)}.</p>}
          {spending.length > 0 && (
            <>
              <div className="h-48">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie data={spending.map((s) => ({ name: s.name, value: toChart(s.amount) }))} dataKey="value" innerRadius={45} outerRadius={75} paddingAngle={2}>
                      {spending.map((s, i) => (
                        <Cell key={s.name} fill={CHART_COLORS[i % CHART_COLORS.length]} />
                      ))}
                    </Pie>
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <ul className="mt-2 space-y-1.5">
                {spending.map((s, i) => (
                  <li key={s.categoryId ?? "none"} className="flex items-center justify-between text-sm">
                    <span className="flex items-center gap-2">
                      <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: CHART_COLORS[i % CHART_COLORS.length] }} />
                      {s.name}
                    </span>
                    <span className="font-medium">{formatVND(s.amount)}</span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </CardContent>
      </Card>

      <div>
        <h2 className="text-lg font-display font-semibold mb-3">Jars this month</h2>
        <div className="grid grid-cols-2 gap-3">
          {(d?.byJar ?? []).map((jar) => (
            <Link key={jar.jarId} to={`/inbox?jarId=${jar.jarId}`}>
              <Card className="h-full hover:shadow-md transition-shadow">
                <CardContent className="p-4">
                  <p className="text-sm font-semibold truncate">{jar.name}</p>
                  <p className="text-base font-display font-bold">{formatVND(jar.balance)}</p>
                  <p className="text-xs text-muted-foreground">Spent {formatVND(jar.spent)}</p>
                </CardContent>
              </Card>
            </Link>
          ))}
        </div>
      </div>

      {d && (
        <Card>
          <CardContent className="p-5">
            <h2 className="font-display font-semibold mb-3">Last 6 months</h2>
            <div className="h-44">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={d.trend.map((t) => ({ month: formatMonthShort(t.month), income: toChart(t.income), expense: toChart(t.expense) }))}>
                  <XAxis dataKey="month" tick={{ fontSize: 11 }} axisLine={false} tickLine={false} />
                  <Tooltip formatter={(v: number) => formatVND(v)} />
                  <Bar dataKey="income" name="Income" fill="hsl(160, 55%, 38%)" radius={[4, 4, 0, 0]} />
                  <Bar dataKey="expense" name="Expenses" fill="hsl(340, 68%, 50%)" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </CardContent>
        </Card>
      )}

      <div>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-lg font-display font-semibold">Recent transactions</h2>
          <Button variant="ghost" size="sm" className="text-xs gap-1 text-primary" onClick={() => navigate("/inbox")}>
            View all <ArrowRight className="h-3 w-3" />
          </Button>
        </div>
        <Card>
          <CardContent className="p-0 divide-y divide-border">
            {recent.isLoading && <Skeleton className="m-4 h-10" />}
            {recent.data?.items.map((tx) => <TransactionRow key={tx.id} tx={tx} />)}
            {recent.data?.items.length === 0 && (
              <p className="p-6 text-center text-sm text-muted-foreground">No transactions yet.</p>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
};

export default Dashboard;
