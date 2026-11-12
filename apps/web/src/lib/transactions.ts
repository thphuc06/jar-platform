import type { Transaction, TransactionType } from "@/api/types";

export const TRANSACTION_TYPE_LABEL: Record<TransactionType, string> = {
  INCOME: "Thu nhập",
  EXPENSE: "Chi tiêu",
  JAR_TRANSFER: "Chuyển hũ",
};

/** The main line of a row: who it was paid to / received from, else the note, else the category. */
export function transactionTitle(tx: Pick<Transaction, "type" | "counterparty" | "note" | "categoryName" | "jarName" | "toJarName">): string {
  if (tx.type === "JAR_TRANSFER") return `${tx.jarName ?? "Hũ"} → ${tx.toJarName ?? "Hũ"}`;
  return tx.counterparty || tx.note || tx.categoryName || TRANSACTION_TYPE_LABEL[tx.type];
}

/** The second line: category, plus the jar for an expense. */
export function transactionSubtitle(tx: Pick<Transaction, "type" | "categoryName" | "jarName">): string {
  if (tx.type === "JAR_TRANSFER") return TRANSACTION_TYPE_LABEL.JAR_TRANSFER;
  const category = tx.categoryName || "Chưa phân loại";
  return tx.jarName ? `${category} · ${tx.jarName}` : category;
}

/** Only an expense can be moved to another category or jar; income changes category only; transfers are fixed. */
export function canRecategorize(tx: Pick<Transaction, "type">): boolean {
  return tx.type !== "JAR_TRANSFER";
}
