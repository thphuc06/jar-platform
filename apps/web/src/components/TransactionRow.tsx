import { ArrowDownRight, ArrowLeftRight, ArrowUpRight } from "lucide-react";
import { formatDateTime, formatVND } from "@/lib/format";
import { describe, type TransactionItem } from "@/lib/transactions";
import { cn } from "@/lib/utils";

const ICON = { "+": ArrowDownRight, "-": ArrowUpRight, "": ArrowLeftRight };
const TONE = {
  "+": "bg-success/10 text-success",
  "-": "bg-destructive/10 text-destructive",
  "": "bg-primary/10 text-primary",
};

/** One transaction in a list. With onSelect it is a button (opens the edit dialog in the Money Inbox). */
const TransactionRow = ({ tx, onSelect }: { tx: TransactionItem; onSelect?: (tx: TransactionItem) => void }) => {
  const line = describe(tx);
  const Icon = ICON[line.sign];
  const content = (
    <>
      <div className={cn("flex h-10 w-10 shrink-0 items-center justify-center rounded-full", TONE[line.sign])}>
        <Icon className="h-4 w-4" />
      </div>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{line.title}</p>
        <p className="truncate text-xs text-muted-foreground">{line.subtitle}</p>
      </div>
      <div className="shrink-0 text-right">
        <p className={cn("whitespace-nowrap text-sm font-semibold", line.sign === "+" && "text-success")}>
          {line.sign}
          {formatVND(tx.amount)}
        </p>
        <p className="whitespace-nowrap text-[10px] text-muted-foreground">{formatDateTime(tx.occurredAt)}</p>
      </div>
    </>
  );
  const className = "flex w-full items-center gap-3 px-4 py-3 text-left";
  return onSelect ? (
    <button type="button" className={cn(className, "transition-colors hover:bg-muted/40")} onClick={() => onSelect(tx)}>
      {content}
    </button>
  ) : (
    <div className={className}>{content}</div>
  );
};

export default TransactionRow;
