import type { components } from "@/api";

export type TransactionItem = components["schemas"]["TransactionItem"];

export interface TransactionLine {
  title: string;
  subtitle: string;
  /** "+" money came in, "-" money went out, "" moved between the user's own jars (total unchanged). */
  sign: "+" | "-" | "";
}

/** How one transaction reads in a list; shared by the Dashboard and the Money Inbox so both say the same thing. */
export function describe(tx: TransactionItem): TransactionLine {
  switch (tx.type) {
    case "INCOME":
      return {
        title: tx.counterparty ?? tx.category?.name ?? "Income",
        subtitle: `${tx.category?.name ?? "Uncategorized"} · into ${tx.jar.name}`,
        sign: "+",
      };
    case "EXPENSE":
      return {
        title: tx.counterparty ?? tx.category?.name ?? "Expense",
        subtitle: `${tx.category?.name ?? "Uncategorized"} · from ${tx.jar.name}`,
        sign: "-",
      };
    case "JAR_TRANSFER":
      return {
        title: `${tx.jar.name} → ${tx.toJar?.name ?? "?"}`,
        subtitle: tx.note ?? "Between your jars",
        sign: "",
      };
  }
}
