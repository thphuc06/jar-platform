import { Loader2, X } from "lucide-react";
import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useCategories, useJars, useTransactionPages, type TransactionFilter } from "@/api/queries";
import AmendTransactionDialog from "@/components/AmendTransactionDialog";
import TransactionRow from "@/components/TransactionRow";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import type { TransactionItem } from "@/lib/transactions";
import { cn } from "@/lib/utils";

const TYPES = [
  { value: undefined, label: "All" },
  { value: "INCOME", label: "Income" },
  { value: "EXPENSE", label: "Expenses" },
  { value: "JAR_TRANSFER", label: "Between jars" },
] as const;

const chip = (active: boolean) =>
  cn(
    "whitespace-nowrap rounded-full px-3 py-1.5 text-xs font-medium transition-colors",
    active ? "bg-primary text-primary-foreground" : "bg-secondary text-secondary-foreground hover:bg-secondary/80",
  );

/**
 * Every transaction, newest first, loaded page by page with the API's cursor. The filters live in the URL
 * (?jarId=&categoryId=&type=): Back works, a link can be shared, and the Dashboard links straight to one jar.
 * There is no text search: the API has none, and filtering only the pages already loaded would silently miss
 * older matches.
 */
const MoneyInbox = () => {
  const [params, setParams] = useSearchParams();
  const filter: TransactionFilter = {
    jarId: params.get("jarId") ?? undefined,
    categoryId: params.get("categoryId") ?? undefined,
    type: (params.get("type") as TransactionFilter["type"]) ?? undefined,
  };
  const pages = useTransactionPages(filter);
  const categories = useCategories();
  const jars = useJars();
  const [editing, setEditing] = useState<TransactionItem | null>(null);

  const setFilter = (key: keyof TransactionFilter, value: string | undefined) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (value) {
          next.set(key, value);
        } else {
          next.delete(key);
        }
        return next;
      },
      { replace: true },
    );

  const items = pages.data?.pages.flatMap((page) => page.items) ?? [];
  const jarName = jars.data?.jars.find((j) => j.id === filter.jarId)?.name;

  return (
    <div className="space-y-5 animate-slide-up pb-32 md:pb-6">
      <div>
        <h1 className="text-2xl font-display font-bold">Money Inbox</h1>
        <p className="text-sm text-muted-foreground">All your transactions, newest first. Tap one to edit it.</p>
      </div>

      {filter.jarId && (
        <button type="button" className={cn(chip(true), "inline-flex items-center gap-1")} onClick={() => setFilter("jarId", undefined)}>
          Jar: {jarName ?? "selected"} <X className="h-3 w-3" aria-label="Remove jar filter" />
        </button>
      )}

      <div className="flex gap-2 overflow-x-auto pb-1 no-scrollbar" role="group" aria-label="Type">
        {TYPES.map((t) => (
          <button key={t.label} type="button" className={chip(filter.type === t.value)} onClick={() => setFilter("type", t.value)}>
            {t.label}
          </button>
        ))}
      </div>

      <div className="flex gap-2 overflow-x-auto pb-1 pr-4 no-scrollbar" role="group" aria-label="Category">
        <button type="button" className={chip(!filter.categoryId)} onClick={() => setFilter("categoryId", undefined)}>
          Every category
        </button>
        {(categories.data?.items ?? []).map((c) => (
          <button key={c.id} type="button" className={chip(filter.categoryId === c.id)} onClick={() => setFilter("categoryId", c.id)}>
            {c.name}
          </button>
        ))}
      </div>

      <Card>
        <CardContent className="divide-y divide-border p-0">
          {pages.isLoading &&
            Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="flex items-center gap-3 px-4 py-3.5">
                <Skeleton className="h-10 w-10 rounded-full" />
                <div className="flex-1 space-y-2">
                  <Skeleton className="h-4 w-3/4" />
                  <Skeleton className="h-3 w-1/2" />
                </div>
              </div>
            ))}
          {pages.isError && (
            <p role="alert" className="p-6 text-center text-sm text-destructive">
              Could not load transactions: {pages.error.message}
            </p>
          )}
          {items.map((tx) => (
            <TransactionRow key={tx.id} tx={tx} onSelect={setEditing} />
          ))}
          {pages.isSuccess && items.length === 0 && (
            <p className="p-8 text-center text-sm text-muted-foreground">No transactions match these filters</p>
          )}
        </CardContent>
      </Card>

      {pages.hasNextPage && (
        <Button variant="outline" className="w-full gap-2" disabled={pages.isFetchingNextPage} onClick={() => pages.fetchNextPage()}>
          {pages.isFetchingNextPage && <Loader2 className="h-4 w-4 animate-spin" />} Load older transactions
        </Button>
      )}

      {editing && <AmendTransactionDialog tx={editing} onClose={() => setEditing(null)} />}
    </div>
  );
};

export default MoneyInbox;
