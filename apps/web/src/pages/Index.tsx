import { ArrowDownRight, ArrowLeftRight, ArrowRight, ArrowUpRight, Bell, Inbox, Loader2, PiggyBank, Shield, Target, TrendingUp } from "lucide-react";
import { motion } from "framer-motion";
import { useNavigate } from "react-router-dom";
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import { useDashboard } from "@/api/dashboard";
import { useMe } from "@/api/me";
import { useRecentTransactions } from "@/api/transactions";
import ErrorNotice from "@/components/ErrorNotice";
import TransactionRow from "@/components/TransactionRow";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { greeting } from "@/lib/dates";
import { CHART_COLORS } from "@/lib/jars";
import { formatAmount, formatMoney, moneyToNumber } from "@/lib/money";

const container = {
  hidden: { opacity: 0 },
  show: { opacity: 1, transition: { staggerChildren: 0.06 } },
};
const item = {
  hidden: { opacity: 0, y: 12 },
  show: { opacity: 1, y: 0 },
};

const quickActions = [
  { icon: ArrowUpRight, label: "Chi tiêu", color: "gradient-premium", to: "/transfer?mode=expense" },
  { icon: ArrowDownRight, label: "Thu nhập", color: "bg-success", to: "/transfer?mode=income" },
  { icon: ArrowLeftRight, label: "Chuyển hũ", color: "bg-info", to: "/transfer?mode=jars" },
];

const Index = () => {
  const navigate = useNavigate();
  const me = useMe();
  const dashboard = useDashboard();
  const recent = useRecentTransactions(5);

  const data = dashboard.data;
  const spending = (data?.spendByCategory ?? [])
    .map((c) => ({ name: c.name, value: moneyToNumber(c.spent) }))
    .filter((c) => c.value > 0)
    .sort((a, b) => b.value - a.value)
    .slice(0, 8);

  const shortcuts = [
    { icon: PiggyBank, label: "Hũ ngân sách", desc: data ? `${data.spendByJar.length} hũ` : "Xem số dư từng hũ", color: "bg-accent/10 text-accent", to: "/jars" },
    { icon: Target, label: "Mục tiêu", desc: "Lập kế hoạch tiết kiệm", color: "bg-primary/10 text-primary", to: "/goals" },
    { icon: Inbox, label: "Hộp tiền", desc: "Tất cả giao dịch", color: "bg-info/10 text-info", to: "/inbox" },
    {
      icon: Bell,
      label: "Cảnh báo",
      desc: data ? (data.openAnomalies > 0 ? `${data.openAnomalies} cảnh báo đang mở` : "Không có cảnh báo mới") : "Chi tiêu bất thường",
      color: "bg-warning/10 text-warning",
      to: "/alerts",
    },
  ];

  const displayName = me.data?.displayName;

  return (
    <motion.div variants={container} initial="hidden" animate="show" className="space-y-6">
      <motion.div variants={item}>
        <h1 className="text-2xl font-display font-bold">
          {greeting(new Date().getHours())}
          {displayName ? `, ${displayName}` : ""}
        </h1>
        <p className="text-sm text-muted-foreground">Tổng quan tài chính của bạn</p>
      </motion.div>

      {dashboard.isError && (
        <ErrorNotice error={dashboard.error} what="tổng quan tài chính" onRetry={() => void dashboard.refetch()} />
      )}

      <motion.div variants={item}>
        <Card className="relative overflow-hidden text-primary-foreground shadow-xl shadow-primary/20 gradient-premium">
          <CardContent className="relative z-10 p-6">
            <div className="mb-1 flex items-center gap-2">
              <Shield className="h-4 w-4 text-primary-foreground/70" aria-hidden="true" />
              <p className="text-sm font-medium text-primary-foreground/80">Tổng số dư</p>
            </div>
            {dashboard.isPending ? (
              <Loader2 className="mt-2 h-6 w-6 animate-spin" aria-label="Đang tải" />
            ) : (
              <p className="mt-1 text-3xl font-display font-bold">{formatMoney(data?.balance)}</p>
            )}
            <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
              <span className="flex items-center gap-1">
                <ArrowDownRight className="h-3 w-3" aria-hidden="true" />
                <span className="sr-only">Thu tháng này:</span>
                {data ? `+${formatMoney(data.income, { signDisplay: "never" })}` : "-"}
              </span>
              <span className="flex items-center gap-1">
                <ArrowUpRight className="h-3 w-3" aria-hidden="true" />
                <span className="sr-only">Chi tháng này:</span>
                {data ? `-${formatMoney(data.expense, { signDisplay: "never" })}` : "-"}
              </span>
              <span className="text-xs text-primary-foreground/70">tháng này</span>
            </div>
          </CardContent>
          <div className="absolute -right-8 -top-8 h-36 w-36 rounded-full bg-primary-foreground/10 blur-sm" />
          <div className="absolute -right-2 bottom-0 h-24 w-24 rounded-full bg-primary-foreground/5" />
        </Card>
      </motion.div>

      <motion.div variants={item}>
        <div className="grid grid-cols-3 gap-3">
          {quickActions.map((action) => (
            <button
              key={action.label}
              type="button"
              className="group flex flex-col items-center gap-2 rounded-xl p-1 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              onClick={() => navigate(action.to)}
            >
              <div className={`flex h-12 w-12 items-center justify-center rounded-2xl shadow-md transition-transform group-hover:scale-105 ${action.color}`}>
                <action.icon className="h-5 w-5 text-primary-foreground" aria-hidden="true" />
              </div>
              <span className="text-xs font-medium text-muted-foreground">{action.label}</span>
            </button>
          ))}
        </div>
      </motion.div>

      {data && data.openAnomalies > 0 && (
        <motion.div variants={item}>
          <Card className="cursor-pointer border-warning/40 bg-warning/5" onClick={() => navigate("/alerts")}>
            <CardContent className="flex items-center gap-3 p-4">
              <Bell className="h-5 w-5 shrink-0 text-warning" aria-hidden="true" />
              <p className="flex-1 text-sm">
                Có <strong>{data.openAnomalies}</strong> cảnh báo chi tiêu bất thường đang chờ bạn xác nhận.
              </p>
              <ArrowRight className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
            </CardContent>
          </Card>
        </motion.div>
      )}

      {spending.length > 0 && (
        <motion.div variants={item}>
          <Card>
            <CardContent className="p-5">
              <h2 className="mb-3 font-display font-semibold">Chi tiêu theo danh mục</h2>
              <div className="h-48" role="img" aria-label="Biểu đồ tròn chi tiêu theo danh mục trong tháng">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie data={spending} cx="50%" cy="50%" innerRadius={45} outerRadius={75} paddingAngle={2} dataKey="value" nameKey="name">
                      {spending.map((entry, i) => (
                        <Cell key={entry.name} fill={CHART_COLORS[i % CHART_COLORS.length]} />
                      ))}
                    </Pie>
                    <Tooltip formatter={(value) => formatAmount(Number(value ?? 0))} />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <ul className="mt-2 flex flex-wrap justify-center gap-x-4 gap-y-1">
                {spending.map((s, i) => (
                  <li key={s.name} className="flex items-center gap-1.5 text-xs">
                    <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: CHART_COLORS[i % CHART_COLORS.length] }} aria-hidden="true" />
                    <span className="text-muted-foreground">
                      {s.name}: {formatAmount(s.value)}
                    </span>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
        </motion.div>
      )}

      <motion.div variants={item}>
        <h2 className="mb-3 text-lg font-display font-semibold">Truy cập nhanh</h2>
        <div className="grid grid-cols-2 gap-3">
          {shortcuts.map((s) => (
            <Card key={s.label} className="group transition-all duration-300 hover:shadow-lg hover:shadow-primary/5">
              <button
                type="button"
                onClick={() => navigate(s.to)}
                className="flex w-full items-center gap-3 rounded-lg p-4 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl transition-transform group-hover:scale-110 ${s.color}`}>
                  <s.icon className="h-5 w-5" aria-hidden="true" />
                </div>
                <div className="min-w-0">
                  <p className="text-sm font-semibold">{s.label}</p>
                  <p className="truncate text-xs text-muted-foreground">{s.desc}</p>
                </div>
              </button>
            </Card>
          ))}
        </div>
      </motion.div>

      <motion.div variants={item}>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-display font-semibold">Giao dịch gần đây</h2>
          <Button variant="ghost" size="sm" className="gap-1 text-xs text-primary" onClick={() => navigate("/inbox")}>
            Xem tất cả <ArrowRight className="h-3 w-3" aria-hidden="true" />
          </Button>
        </div>
        <Card>
          <CardContent className="divide-y divide-border p-0">
            {recent.isPending && (
              <div className="space-y-3 p-4">
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </div>
            )}
            {recent.isError && (
              <div className="p-4">
                <ErrorNotice error={recent.error} what="giao dịch gần đây" onRetry={() => void recent.refetch()} />
              </div>
            )}
            {recent.data?.map((tx) => <TransactionRow key={tx.id} tx={tx} />)}
            {recent.data?.length === 0 && (
              <div className="flex flex-col items-center gap-2 p-8 text-center text-sm text-muted-foreground">
                <TrendingUp className="h-5 w-5" aria-hidden="true" />
                Chưa có giao dịch nào. Hãy ghi khoản thu hoặc chi đầu tiên của bạn.
              </div>
            )}
          </CardContent>
        </Card>
      </motion.div>
    </motion.div>
  );
};

export default Index;
