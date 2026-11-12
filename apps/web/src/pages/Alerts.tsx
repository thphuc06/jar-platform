import { useMemo, useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { AlertTriangle, Bell, CheckCheck, Info, Loader2, X, XOctagon } from "lucide-react";
import { formatDistanceToNow } from "date-fns";
import { useAnomalies, useAnomalyFeedback } from "@/api/anomalies";
import { isUnread, useMarkNotificationRead, useNotifications, useUnreadCount } from "@/api/notifications";
import { flattenPages } from "@/api/paging";
import { describeError } from "@/api/problem";
import type { Anomaly, AnomalyStatus, Notification } from "@/api/types";
import ErrorNotice from "@/components/ErrorNotice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/hooks/use-toast";
import { ANOMALY_STATUS_LABEL, anomalyHeadline, FEEDBACK_OPTIONS, SEVERITY_LABEL } from "@/lib/anomalies";
import { viLocale } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { cn } from "@/lib/utils";

const severityConfig = {
  high: {
    icon: XOctagon,
    card: "border-destructive/30 bg-destructive/5",
    iconBox: "bg-destructive/20 text-destructive",
    badge: "destructive" as const,
  },
  medium: {
    icon: AlertTriangle,
    card: "border-warning/30 bg-warning/5",
    iconBox: "bg-warning/20 text-warning",
    badge: "default" as const,
  },
  low: {
    icon: Info,
    card: "border-info/30 bg-info/5",
    iconBox: "bg-info/20 text-info",
    badge: "secondary" as const,
  },
};

const configFor = (severity: string) => severityConfig[severity.toLowerCase() as keyof typeof severityConfig] ?? severityConfig.low;

const STATUS_FILTERS: AnomalyStatus[] = ["OPEN", "CONFIRMED", "EXPECTED", "FALSE_POSITIVE"];

type Tab = "anomalies" | "notifications";

const ago = (iso: string) => formatDistanceToNow(new Date(iso), { addSuffix: true, locale: viLocale });

const ListSkeleton = () => (
  <div className="space-y-3">
    {Array.from({ length: 3 }).map((_, i) => (
      <Card key={i}>
        <CardContent className="flex items-start gap-3 p-4">
          <Skeleton className="h-9 w-9 shrink-0 rounded-full" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-4 w-3/4" />
            <Skeleton className="h-3 w-full" />
            <Skeleton className="h-3 w-1/3" />
          </div>
        </CardContent>
      </Card>
    ))}
  </div>
);

// ---- anomalies ------------------------------------------------------------------------------------------------

const AnomalyCard = ({ anomaly }: { anomaly: Anomaly }) => {
  const { toast } = useToast();
  const feedback = useAnomalyFeedback();
  const config = configFor(anomaly.severity);
  const Icon = config.icon;
  const isOpen = anomaly.status === "OPEN";

  return (
    <motion.div initial={{ opacity: 0, y: 12 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.25 }}>
      <Card className={isOpen ? config.card : "border-border bg-muted/30"}>
        <CardContent className="flex items-start gap-3 p-3 sm:p-4">
          <div className={cn("flex h-9 w-9 shrink-0 items-center justify-center rounded-full", isOpen ? config.iconBox : "bg-muted text-muted-foreground")}>
            <Icon className="h-4 w-4" aria-hidden="true" />
          </div>
          <div className="min-w-0 flex-1 space-y-2">
            <div className="flex flex-wrap items-start gap-2">
              <h3 className="min-w-0 break-words text-sm font-semibold leading-snug">{anomalyHeadline(anomaly)}</h3>
              <Badge variant={config.badge} className="shrink-0 px-1.5 py-0 text-[10px]">
                {SEVERITY_LABEL[anomaly.severity.toUpperCase()] ?? anomaly.severity}
              </Badge>
              {!isOpen && (
                <Badge variant="outline" className="shrink-0 px-1.5 py-0 text-[10px]">
                  {ANOMALY_STATUS_LABEL[anomaly.status]}
                </Badge>
              )}
            </div>
            <p className="text-xs text-muted-foreground">
              Khoản chi {formatMoney(anomaly.amount)} · {ago(anomaly.detectedAt)}
            </p>
            <div role="group" aria-label="Nhận xét của bạn về cảnh báo này" className="flex flex-wrap gap-2 pt-1">
              {FEEDBACK_OPTIONS.map((option) => (
                <Button
                  key={option.label}
                  size="sm"
                  variant={anomaly.status === option.label ? "default" : "outline"}
                  className="h-8 text-xs"
                  disabled={feedback.isPending || anomaly.status === option.label}
                  onClick={() =>
                    feedback.mutate(
                      { id: anomaly.id, label: option.label },
                      {
                        onSuccess: () => toast({ title: "Đã ghi nhận", description: `Cảnh báo: ${option.text.toLowerCase()}` }),
                        onError: (error) => toast({ title: "Không gửi được nhận xét", description: describeError(error), variant: "destructive" }),
                      },
                    )
                  }
                >
                  {option.text}
                </Button>
              ))}
            </div>
          </div>
        </CardContent>
      </Card>
    </motion.div>
  );
};

const AnomalyList = () => {
  const [status, setStatus] = useState<AnomalyStatus>("OPEN");
  const list = useAnomalies(status);
  const items = useMemo(() => flattenPages(list.data), [list.data]);

  return (
    <div className="space-y-4">
      <div role="group" aria-label="Trạng thái cảnh báo" className="no-scrollbar flex gap-2 overflow-x-auto pb-1">
        {STATUS_FILTERS.map((s) => (
          <button
            key={s}
            type="button"
            aria-pressed={status === s}
            onClick={() => setStatus(s)}
            className={cn(
              "whitespace-nowrap rounded-full px-3 py-1.5 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              status === s ? "bg-primary text-primary-foreground" : "bg-secondary text-secondary-foreground hover:bg-secondary/80",
            )}
          >
            {ANOMALY_STATUS_LABEL[s]}
          </button>
        ))}
      </div>

      {list.isPending && <ListSkeleton />}
      {list.isError && <ErrorNotice error={list.error} what="danh sách cảnh báo" onRetry={() => void list.refetch()} />}
      {list.data && (
        <div className="space-y-3">
          {items.map((anomaly) => (
            <AnomalyCard key={anomaly.id} anomaly={anomaly} />
          ))}
          {items.length === 0 && (
            <div className="py-12 text-center text-sm text-muted-foreground">
              {status === "OPEN" ? "Không có cảnh báo nào đang chờ bạn" : "Không có cảnh báo nào ở trạng thái này"}
            </div>
          )}
          {list.hasNextPage && (
            <Button variant="outline" className="w-full gap-2" onClick={() => void list.fetchNextPage()} disabled={list.isFetchingNextPage}>
              {list.isFetchingNextPage && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
              Tải thêm
            </Button>
          )}
        </div>
      )}
    </div>
  );
};

// ---- notifications --------------------------------------------------------------------------------------------

const NotificationCard = ({ notification, onRead, pending }: { notification: Notification; onRead: () => void; pending: boolean }) => {
  const unread = isUnread(notification);
  const config = configFor(notification.severity);
  const Icon = config.icon;

  return (
    <motion.div
      layout
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: unread ? 1 : 0.6, y: 0 }}
      exit={{ opacity: 0, height: 0, marginBottom: 0 }}
      transition={{ duration: 0.25 }}
    >
      <Card className={unread ? config.card : "border-border bg-muted/30"}>
        <CardContent className="flex items-start gap-3 p-3 sm:p-4">
          <div className={cn("flex h-9 w-9 shrink-0 items-center justify-center rounded-full", unread ? config.iconBox : "bg-muted text-muted-foreground")}>
            <Icon className="h-4 w-4" aria-hidden="true" />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-start justify-between gap-2">
              <div className="flex flex-wrap items-start gap-2">
                <h3 className="min-w-0 break-words text-sm font-semibold leading-snug">{notification.title}</h3>
                {unread && (
                  <Badge variant={config.badge} className="shrink-0 px-1.5 py-0 text-[10px]">
                    {SEVERITY_LABEL[notification.severity.toUpperCase()] ?? notification.severity}
                  </Badge>
                )}
              </div>
              {unread && (
                <Button variant="ghost" size="icon" className="h-7 w-7 shrink-0" onClick={onRead} disabled={pending}>
                  <X className="h-3 w-3" aria-hidden="true" />
                  <span className="sr-only">Đánh dấu đã đọc: {notification.title}</span>
                </Button>
              )}
            </div>
            {notification.body && <p className="mt-1 break-words text-xs leading-relaxed text-muted-foreground">{notification.body}</p>}
            <p className="mt-2 text-[10px] text-muted-foreground/70">{ago(notification.createdAt)}</p>
          </div>
        </CardContent>
      </Card>
    </motion.div>
  );
};

const NotificationList = () => {
  const { toast } = useToast();
  const list = useNotifications();
  const markRead = useMarkNotificationRead();
  const items = useMemo(() => flattenPages(list.data), [list.data]);
  const unread = items.filter(isUnread);
  const read = items.filter((n) => !isUnread(n));

  const read1 = (id: string) =>
    markRead.mutate(id, {
      onError: (error) => toast({ title: "Không đánh dấu được đã đọc", description: describeError(error), variant: "destructive" }),
    });

  return (
    <div className="space-y-5">
      {list.isPending && <ListSkeleton />}
      {list.isError && <ErrorNotice error={list.error} what="thông báo" onRetry={() => void list.refetch()} />}
      {list.data && (
        <>
          {unread.length > 0 && (
            <section aria-label="Chưa đọc" className="space-y-3">
              <div className="flex items-center gap-2">
                <Bell className="h-4 w-4 text-destructive" aria-hidden="true" />
                <h2 className="text-sm font-medium">Chưa đọc</h2>
              </div>
              <AnimatePresence initial={false}>
                {unread.map((n) => (
                  <NotificationCard key={n.id} notification={n} onRead={() => read1(n.id)} pending={markRead.isPending} />
                ))}
              </AnimatePresence>
            </section>
          )}

          {read.length > 0 && (
            <section aria-label="Đã đọc" className="space-y-3">
              <div className="flex items-center gap-2">
                <CheckCheck className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
                <h2 className="text-sm font-medium text-muted-foreground">Đã đọc</h2>
              </div>
              {read.map((n) => (
                <NotificationCard key={n.id} notification={n} onRead={() => undefined} pending={false} />
              ))}
            </section>
          )}

          {items.length === 0 && <div className="py-12 text-center text-sm text-muted-foreground">Chưa có thông báo nào</div>}

          {list.hasNextPage && (
            <Button variant="outline" className="w-full gap-2" onClick={() => void list.fetchNextPage()} disabled={list.isFetchingNextPage}>
              {list.isFetchingNextPage && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
              Tải thêm
            </Button>
          )}
        </>
      )}
    </div>
  );
};

// ---- page -----------------------------------------------------------------------------------------------------

const Alerts = () => {
  const [tab, setTab] = useState<Tab>("anomalies");
  const unreadCount = useUnreadCount();

  const tabs: Array<{ value: Tab; label: string }> = [
    { value: "anomalies", label: "Chi tiêu bất thường" },
    { value: "notifications", label: "Thông báo" },
  ];

  return (
    <div className="animate-slide-up space-y-5 pb-32 md:pb-6">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-display font-bold">Cảnh báo</h1>
          <p className="text-sm text-muted-foreground">Chi tiêu bất thường và thông báo của bạn</p>
        </div>
        {unreadCount > 0 && (
          <Badge variant="destructive" className="shrink-0 text-xs">
            {unreadCount} mới
          </Badge>
        )}
      </div>

      <div role="tablist" aria-label="Loại cảnh báo" className="grid grid-cols-2 gap-1 rounded-xl bg-secondary p-1">
        {tabs.map((t) => (
          <button
            key={t.value}
            type="button"
            role="tab"
            id={`alerts-tab-${t.value}`}
            aria-selected={tab === t.value}
            aria-controls={`alerts-panel-${t.value}`}
            onClick={() => setTab(t.value)}
            className={cn(
              "rounded-lg px-3 py-2 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              tab === t.value ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
            )}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div role="tabpanel" id={`alerts-panel-${tab}`} aria-labelledby={`alerts-tab-${tab}`}>
        {tab === "anomalies" ? <AnomalyList /> : <NotificationList />}
      </div>
    </div>
  );
};

export default Alerts;
