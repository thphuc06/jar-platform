import { useMemo, useState } from "react";
import { Loader2, Pencil, Search } from "lucide-react";
import { useCategories } from "@/api/categories";
import { useJars } from "@/api/jars";
import { flattenPages } from "@/api/paging";
import { useTransactionsInfinite, type TransactionFilters } from "@/api/transactions";
import type { Transaction, TransactionType } from "@/api/types";
import ErrorNotice from "@/components/ErrorNotice";
import RecategorizeDialog from "@/components/RecategorizeDialog";
import TransactionRow from "@/components/TransactionRow";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { canRecategorize, TRANSACTION_TYPE_LABEL, transactionTitle } from "@/lib/transactions";
import { cn } from "@/lib/utils";

type TypeFilter = TransactionType | "ALL";

const TYPE_FILTERS: Array<{ value: TypeFilter; label: string }> = [
  { value: "ALL", label: "Tất cả" },
  { value: "EXPENSE", label: TRANSACTION_TYPE_LABEL.EXPENSE },
  { value: "INCOME", label: TRANSACTION_TYPE_LABEL.INCOME },
  { value: "JAR_TRANSFER", label: TRANSACTION_TYPE_LABEL.JAR_TRANSFER },
];

const PERIODS = [
  { value: "all", label: "Mọi lúc", days: null },
  { value: "7", label: "7 ngày qua", days: 7 },
  { value: "30", label: "30 ngày qua", days: 30 },
  { value: "90", label: "90 ngày qua", days: 90 },
] as const;

const ALL = "ALL";

const chipClass = (active: boolean) =>
  cn(
    "whitespace-nowrap rounded-full px-3 py-1.5 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
    active ? "bg-primary text-primary-foreground" : "bg-secondary text-secondary-foreground hover:bg-secondary/80",
  );

const MoneyInbox = () => {
  const [search, setSearch] = useState("");
  const [type, setType] = useState<TypeFilter>("ALL");
  const [categoryId, setCategoryId] = useState<string | undefined>();
  const [jarId, setJarId] = useState<string | undefined>();
  // `from` is fixed when the period is picked, so the query key does not change on every render
  const [period, setPeriod] = useState<{ value: string; from?: string }>({ value: "all" });
  const [editing, setEditing] = useState<Transaction | null>(null);

  const filters = useMemo<TransactionFilters>(
    () => ({ type: type === "ALL" ? undefined : type, categoryId, jarId, from: period.from }),
    [type, categoryId, jarId, period.from],
  );
  const list = useTransactionsInfinite(filters);
  const categories = useCategories();
  const jars = useJars();

  const rows = useMemo(() => flattenPages(list.data), [list.data]);
  const needle = search.trim().toLowerCase();
  // the API has no free-text filter: this searches the rows that are loaded
  const visible = needle
    ? rows.filter((tx) =>
        [tx.counterparty, tx.note, tx.categoryName, tx.jarName, tx.toJarName].some((v) => v?.toLowerCase().includes(needle)),
      )
    : rows;

  const categoryChips = (categories.data ?? []).filter((c) => type === "ALL" || c.kind === type);

  const pickType = (next: TypeFilter) => {
    setType(next);
    setCategoryId(undefined); // a category of the other kind would match nothing
  };

  const pickPeriod = (value: string) => {
    const days = PERIODS.find((p) => p.value === value)?.days ?? null;
    setPeriod({ value, from: days === null ? undefined : new Date(Date.now() - days * 86_400_000).toISOString() });
  };

  return (
    <div className="animate-slide-up space-y-5 pb-32 md:pb-6">
      <div>
        <h1 className="text-2xl font-display font-bold">Hộp tiền</h1>
        <p className="text-sm text-muted-foreground">Mọi giao dịch của bạn ở một nơi</p>
      </div>

      <div className="relative">
        <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
        <Input
          type="search"
          aria-label="Tìm trong các giao dịch đã tải"
          placeholder="Tìm giao dịch..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="pl-9"
        />
      </div>

      <div role="group" aria-label="Loại giao dịch" className="no-scrollbar flex gap-2 overflow-x-auto pb-1 pr-4">
        {TYPE_FILTERS.map((f) => (
          <button key={f.value} type="button" aria-pressed={type === f.value} onClick={() => pickType(f.value)} className={chipClass(type === f.value)}>
            {f.label}
          </button>
        ))}
      </div>

      {categoryChips.length > 0 && (
        <div role="group" aria-label="Danh mục" className="no-scrollbar flex gap-2 overflow-x-auto pb-1 pr-4">
          <button type="button" aria-pressed={categoryId === undefined} onClick={() => setCategoryId(undefined)} className={chipClass(categoryId === undefined)}>
            Mọi danh mục
          </button>
          {categoryChips.map((c) => (
            <button key={c.id} type="button" aria-pressed={categoryId === c.id} onClick={() => setCategoryId(c.id)} className={chipClass(categoryId === c.id)}>
              {c.name}
            </button>
          ))}
        </div>
      )}

      <div className="grid grid-cols-2 gap-3">
        <Select value={jarId ?? ALL} onValueChange={(v) => setJarId(v === ALL ? undefined : v)}>
          <SelectTrigger aria-label="Lọc theo hũ" className="h-10">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>Mọi hũ</SelectItem>
            {(jars.data ?? []).map((j) => (
              <SelectItem key={j.id} value={j.id}>
                {j.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select value={period.value} onValueChange={pickPeriod}>
          <SelectTrigger aria-label="Lọc theo thời gian" className="h-10">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {PERIODS.map((p) => (
              <SelectItem key={p.value} value={p.value}>
                {p.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {list.isError ? (
        <ErrorNotice error={list.error} what="danh sách giao dịch" onRetry={() => void list.refetch()} />
      ) : (
        <Card>
          <CardContent className="divide-y divide-border p-0">
            {list.isPending &&
              Array.from({ length: 5 }).map((_, i) => (
                <div key={i} className="flex items-center gap-3 px-4 py-3.5">
                  <Skeleton className="h-10 w-10 rounded-full" />
                  <div className="flex-1 space-y-2">
                    <Skeleton className="h-4 w-3/4" />
                    <Skeleton className="h-3 w-1/2" />
                  </div>
                </div>
              ))}

            {visible.map((tx) => (
              <TransactionRow
                key={tx.id}
                tx={tx}
                action={
                  canRecategorize(tx) ? (
                    <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0" onClick={() => setEditing(tx)}>
                      <Pencil className="h-3.5 w-3.5" aria-hidden="true" />
                      <span className="sr-only">Đổi danh mục: {transactionTitle(tx)}</span>
                    </Button>
                  ) : undefined
                }
              />
            ))}

            {!list.isPending && visible.length === 0 && (
              <div className="p-8 text-center text-sm text-muted-foreground">
                {rows.length > 0 ? "Không có giao dịch nào khớp với từ khóa" : "Không có giao dịch nào"}
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {list.hasNextPage && (
        <Button variant="outline" className="w-full gap-2" onClick={() => void list.fetchNextPage()} disabled={list.isFetchingNextPage}>
          {list.isFetchingNextPage && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
          Tải thêm giao dịch
        </Button>
      )}

      <RecategorizeDialog tx={editing} onClose={() => setEditing(null)} />
    </div>
  );
};

export default MoneyInbox;
