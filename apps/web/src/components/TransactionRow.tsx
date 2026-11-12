import type { ReactNode } from "react";
import { ArrowDownRight, ArrowRightLeft, ArrowUpRight } from "lucide-react";
import type { Transaction } from "@/api/types";
import { formatDate } from "@/lib/dates";
import { formatMoney } from "@/lib/money";
import { transactionSubtitle, transactionTitle } from "@/lib/transactions";

const ICON = {
  INCOME: { Icon: ArrowDownRight, box: "bg-success/10", color: "text-success" },
  EXPENSE: { Icon: ArrowUpRight, box: "bg-destructive/10", color: "text-destructive" },
  JAR_TRANSFER: { Icon: ArrowRightLeft, box: "bg-info/10", color: "text-info" },
} as const;

interface Props {
  tx: Transaction;
  /** Shown to the right of the amount, e.g. an edit button */
  action?: ReactNode;
  showDate?: boolean;
}

/** One transaction: direction icon, title, category/jar, signed amount and date. */
const TransactionRow = ({ tx, action, showDate = true }: Props) => {
  const { Icon, box, color } = ICON[tx.type];
  const sign = tx.type === "INCOME" ? "+" : tx.type === "EXPENSE" ? "-" : "";

  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full ${box}`}>
        <Icon className={`h-4 w-4 ${color}`} aria-hidden="true" />
      </div>
      <div className="min-w-0 flex-1">
        <p className="break-words text-sm font-medium leading-snug">{transactionTitle(tx)}</p>
        <p className="truncate text-xs text-muted-foreground">{transactionSubtitle(tx)}</p>
      </div>
      <div className="flex shrink-0 flex-col items-end gap-0.5 text-right">
        <span className={`whitespace-nowrap text-sm font-semibold ${tx.type === "INCOME" ? "text-success" : ""}`}>
          {sign}
          {formatMoney(tx.amount, { signDisplay: "never" })}
        </span>
        {showDate && <span className="whitespace-nowrap text-[10px] text-muted-foreground">{formatDate(tx.occurredAt)}</span>}
      </div>
      {action}
    </div>
  );
};

export default TransactionRow;
