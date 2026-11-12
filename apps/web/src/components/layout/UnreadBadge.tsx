import { useUnreadCount } from "@/api/notifications";
import { cn } from "@/lib/utils";

/** Number of unread notifications, driven by `unreadCount` of GET /notifications and kept current by the SSE stream. */
const UnreadBadge = ({ className }: { className?: string }) => {
  const count = useUnreadCount();
  if (count <= 0) return null;
  return (
    <span
      className={cn(
        "flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold leading-none text-destructive-foreground",
        className,
      )}
    >
      <span aria-hidden="true">{count > 99 ? "99+" : count}</span>
      <span className="sr-only">{count} thông báo chưa đọc</span>
    </span>
  );
};

export default UnreadBadge;
