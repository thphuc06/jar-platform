import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { AnimatePresence, motion } from "framer-motion";
import { ArrowLeft, ChevronRight, Landmark, Loader2, Send } from "lucide-react";
import { useCategories } from "@/api/categories";
import { useIntentKey } from "@/api/idempotency";
import { useJars, useTransferBetweenJars } from "@/api/jars";
import { describeError } from "@/api/problem";
import { usePostTransaction } from "@/api/transactions";
import type { Jar, Posted, PostTransactionBody } from "@/api/types";
import AmountField from "@/components/AmountField";
import ErrorNotice from "@/components/ErrorNotice";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/hooks/use-toast";
import { jarColor } from "@/lib/jars";
import { compareMoney, formatMoney, parseVndInput } from "@/lib/money";
import { cn } from "@/lib/utils";

type Mode = "expense" | "income" | "jars";

const MODES: Array<{ value: Mode; label: string }> = [
  { value: "expense", label: "Chi tiêu" },
  { value: "income", label: "Thu nhập" },
  { value: "jars", label: "Chuyển hũ" },
];

const BANKS = [
  "Vietcombank", "Techcombank", "MB Bank", "BIDV", "VPBank",
  "ACB", "Sacombank", "TPBank", "VIB", "Agribank",
];

const NO_JAR = "NONE";

function parseMode(value: string | null): Mode {
  return value === "income" || value === "jars" ? value : "expense";
}

/** The success message of a money POST. A replay means the server had already booked this very intent. */
function useDone() {
  const navigate = useNavigate();
  const { toast } = useToast();
  return (posted: Posted, title: string, description: string) => {
    toast(
      posted.replayed
        ? { title: "Giao dịch này đã được ghi trước đó", description: "Hệ thống không ghi lại lần thứ hai." }
        : { title, description },
    );
    navigate("/");
  };
}

function useFailed() {
  const { toast } = useToast();
  return (title: string) => (error: unknown) => toast({ title, description: describeError(error), variant: "destructive" });
}

const SubmitButton = ({ pending, disabled, children }: { pending: boolean; disabled: boolean; children: string }) => (
  <Button type="submit" className="h-12 w-full gap-2 border-0 text-base shadow-lg shadow-primary/20 gradient-premium" disabled={disabled || pending}>
    {pending ? (
      <>
        <Loader2 className="h-5 w-5 animate-spin" aria-hidden="true" /> Đang xử lý...
      </>
    ) : (
      <>
        <Send className="h-5 w-5" aria-hidden="true" /> {children}
      </>
    )}
  </Button>
);

// ---- expense: pick a jar, then recipient and amount ----------------------------------------------------------

const ExpenseForm = ({ jars }: { jars: Jar[] }) => {
  const done = useDone();
  const failed = useFailed();
  const categories = useCategories();
  const post = usePostTransaction();
  const intent = useIntentKey();

  const [jarId, setJarId] = useState<string | null>(null);
  const [bank, setBank] = useState("");
  const [accountNumber, setAccountNumber] = useState("");
  const [amount, setAmount] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [description, setDescription] = useState("");

  const jar = jars.find((j) => j.id === jarId);
  const value = parseVndInput(amount);
  const valid = Boolean(jar && bank && accountNumber.trim() && value);

  if (!jar) {
    return (
      <div className="space-y-2">
        <p className="text-sm text-muted-foreground">Chọn hũ để chi tiền</p>
        {jars.map((j, i) => (
          <Card key={j.id} className="group overflow-hidden transition-all duration-200 hover:shadow-md">
            <div className="h-1" style={{ backgroundColor: jarColor(j, i) }} />
            <button
              type="button"
              onClick={() => setJarId(j.id)}
              className="flex w-full items-center justify-between p-4 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl text-xs font-bold text-primary-foreground" style={{ backgroundColor: jarColor(j, i) }} aria-hidden="true">
                  {j.name.charAt(0)}
                </div>
                <div>
                  <p className="text-sm font-semibold">{j.name}</p>
                  <p className="text-xs text-muted-foreground">Khả dụng: {formatMoney(j.balance)}</p>
                </div>
              </div>
              <ChevronRight className="h-4 w-4 text-muted-foreground transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
            </button>
          </Card>
        ))}
      </div>
    );
  }

  const overBalance = value !== null && compareMoney({ amount: value, currency: "VND" }, jar.balance) > 0;
  const body: PostTransactionBody | null = value
    ? {
        type: "EXPENSE",
        amount: value,
        jarId: jar.id,
        categoryId: categoryId || undefined,
        counterparty: `${accountNumber.trim()} (${bank})`.slice(0, 120),
        note: description.trim() || undefined,
      }
    : null;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!body || !valid) return;
    // same key while the form is unchanged (a retry after an error), a new one after any edit
    const idempotencyKey = intent.get(JSON.stringify(body));
    post.mutate(
      { body, idempotencyKey },
      {
        onSuccess: (posted) => {
          intent.clear();
          done(posted, "Đã ghi khoản chi", `${formatMoney({ amount: body.amount, currency: "VND" })} → ${body.counterparty}`);
        },
        onError: failed("Không ghi được khoản chi"),
      },
    );
  };

  return (
    <form onSubmit={submit} className="space-y-4" noValidate>
      <Card className="border-primary/20">
        <CardContent className="flex items-center gap-3 p-4">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl gradient-premium">
            <Landmark className="h-5 w-5 text-primary-foreground" aria-hidden="true" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-xs text-muted-foreground">Chi từ hũ</p>
            <p className="text-sm font-semibold">
              {jar.name} — Khả dụng: {formatMoney(jar.balance)}
            </p>
          </div>
          <Button type="button" variant="ghost" size="sm" onClick={() => setJarId(null)}>
            Đổi hũ
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-4 p-5">
          <div className="space-y-2">
            <Label htmlFor="expense-bank">Ngân hàng nhận</Label>
            <Select value={bank} onValueChange={setBank}>
              <SelectTrigger id="expense-bank" className="h-11">
                <SelectValue placeholder="Chọn ngân hàng..." />
              </SelectTrigger>
              <SelectContent>
                {BANKS.map((b) => (
                  <SelectItem key={b} value={b}>
                    {b}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="expense-account">Số tài khoản</Label>
            <Input id="expense-account" inputMode="numeric" autoComplete="off" placeholder="Nhập số tài khoản người nhận" value={accountNumber} onChange={(e) => setAccountNumber(e.target.value)} className="h-11" maxLength={60} />
          </div>

          <AmountField
            id="expense-amount"
            value={amount}
            onChange={setAmount}
            quick={[500_000, 1_000_000, 5_000_000]}
            invalid={overBalance ? `Vượt số dư của hũ (${formatMoney(jar.balance)}). Khoản chi vẫn được ghi và làm số dư hũ âm.` : null}
          />

          <div className="space-y-2">
            <Label htmlFor="expense-category">Danh mục</Label>
            <Select value={categoryId} onValueChange={setCategoryId}>
              <SelectTrigger id="expense-category" className="h-11">
                <SelectValue placeholder="Chọn danh mục (không bắt buộc)..." />
              </SelectTrigger>
              <SelectContent>
                {(categories.data ?? [])
                  .filter((c) => c.kind === "EXPENSE")
                  .map((c) => (
                    <SelectItem key={c.id} value={c.id}>
                      {c.name}
                    </SelectItem>
                  ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="expense-note">Nội dung chuyển khoản</Label>
            <Input id="expense-note" placeholder="Ví dụ: Tiền nhà tháng 3" value={description} onChange={(e) => setDescription(e.target.value)} className="h-11" maxLength={500} />
          </div>
        </CardContent>
      </Card>

      <SubmitButton pending={post.isPending} disabled={!valid}>
        Xác nhận chi tiền
      </SubmitButton>
    </form>
  );
};

// ---- income ---------------------------------------------------------------------------------------------------

const IncomeForm = ({ jars }: { jars: Jar[] }) => {
  const done = useDone();
  const failed = useFailed();
  const categories = useCategories();
  const post = usePostTransaction();
  const intent = useIntentKey();

  const [source, setSource] = useState("");
  const [amount, setAmount] = useState("");
  const [jarId, setJarId] = useState(NO_JAR);
  const [categoryId, setCategoryId] = useState("");
  const [note, setNote] = useState("");

  const value = parseVndInput(amount);

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!value) return;
    const body: PostTransactionBody = {
      type: "INCOME",
      amount: value,
      jarId: jarId === NO_JAR ? undefined : jarId,
      categoryId: categoryId || undefined,
      counterparty: source.trim() || undefined,
      note: note.trim() || undefined,
    };
    const idempotencyKey = intent.get(JSON.stringify(body));
    post.mutate(
      { body, idempotencyKey },
      {
        onSuccess: (posted) => {
          intent.clear();
          done(posted, "Đã ghi khoản thu", formatMoney({ amount: value, currency: "VND" }));
        },
        onError: failed("Không ghi được khoản thu"),
      },
    );
  };

  return (
    <form onSubmit={submit} className="space-y-4" noValidate>
      <Card>
        <CardContent className="space-y-4 p-5">
          <div className="space-y-2">
            <Label htmlFor="income-source">Nguồn thu</Label>
            <Input id="income-source" placeholder="Ví dụ: Lương tháng 3" value={source} onChange={(e) => setSource(e.target.value)} className="h-11" maxLength={120} />
          </div>

          <AmountField id="income-amount" value={amount} onChange={setAmount} quick={[5_000_000, 10_000_000, 20_000_000]} />

          <div className="space-y-2">
            <Label htmlFor="income-jar">Cho vào hũ</Label>
            <Select value={jarId} onValueChange={setJarId}>
              <SelectTrigger id="income-jar" className="h-11">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={NO_JAR}>Chưa vào hũ nào (chỉ ghi nhận thu nhập)</SelectItem>
                {jars.map((j) => (
                  <SelectItem key={j.id} value={j.id}>
                    {j.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">Muốn chia khoản thu vào nhiều hũ cùng lúc? Dùng "Phân bổ thu nhập" ở màn hình Hũ ngân sách.</p>
          </div>

          <div className="space-y-2">
            <Label htmlFor="income-category">Danh mục</Label>
            <Select value={categoryId} onValueChange={setCategoryId}>
              <SelectTrigger id="income-category" className="h-11">
                <SelectValue placeholder="Chọn danh mục (không bắt buộc)..." />
              </SelectTrigger>
              <SelectContent>
                {(categories.data ?? [])
                  .filter((c) => c.kind === "INCOME")
                  .map((c) => (
                    <SelectItem key={c.id} value={c.id}>
                      {c.name}
                    </SelectItem>
                  ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="income-note">Ghi chú</Label>
            <Input id="income-note" value={note} onChange={(e) => setNote(e.target.value)} className="h-11" maxLength={500} />
          </div>
        </CardContent>
      </Card>

      <SubmitButton pending={post.isPending} disabled={!value}>
        Ghi khoản thu
      </SubmitButton>
    </form>
  );
};

// ---- jar to jar -----------------------------------------------------------------------------------------------

const JarTransferForm = ({ jars }: { jars: Jar[] }) => {
  const done = useDone();
  const failed = useFailed();
  const transfer = useTransferBetweenJars();
  const intent = useIntentKey();

  const [fromJarId, setFromJarId] = useState("");
  const [toJarId, setToJarId] = useState("");
  const [amount, setAmount] = useState("");
  const [note, setNote] = useState("");

  const from = jars.find((j) => j.id === fromJarId);
  const value = parseVndInput(amount);
  const insufficient = from && value ? compareMoney({ amount: value, currency: "VND" }, from.balance) > 0 : false;
  const valid = Boolean(from && toJarId && toJarId !== fromJarId && value && !insufficient);

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!valid || !value) return;
    const body = { fromJarId, toJarId, amount: value, note: note.trim() || undefined };
    const idempotencyKey = intent.get(JSON.stringify(body));
    transfer.mutate(
      { body, idempotencyKey },
      {
        onSuccess: (posted) => {
          intent.clear();
          const to = jars.find((j) => j.id === toJarId);
          done(posted, "Đã chuyển giữa các hũ", `${formatMoney({ amount: value, currency: "VND" })}: ${from?.name} → ${to?.name}`);
        },
        onError: failed("Không chuyển được tiền giữa các hũ"),
      },
    );
  };

  return (
    <form onSubmit={submit} className="space-y-4" noValidate>
      <Card>
        <CardContent className="space-y-4 p-5">
          <div className="space-y-2">
            <Label htmlFor="transfer-from">Từ hũ</Label>
            <Select value={fromJarId} onValueChange={setFromJarId}>
              <SelectTrigger id="transfer-from" className="h-11">
                <SelectValue placeholder="Chọn hũ nguồn..." />
              </SelectTrigger>
              <SelectContent>
                {jars.map((j) => (
                  <SelectItem key={j.id} value={j.id}>
                    {j.name} — {formatMoney(j.balance)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label htmlFor="transfer-to">Đến hũ</Label>
            <Select value={toJarId} onValueChange={setToJarId}>
              <SelectTrigger id="transfer-to" className="h-11">
                <SelectValue placeholder="Chọn hũ nhận..." />
              </SelectTrigger>
              <SelectContent>
                {jars
                  .filter((j) => j.id !== fromJarId)
                  .map((j) => (
                    <SelectItem key={j.id} value={j.id}>
                      {j.name}
                    </SelectItem>
                  ))}
              </SelectContent>
            </Select>
          </div>

          <AmountField
            id="transfer-amount"
            value={amount}
            onChange={setAmount}
            quick={[100_000, 500_000, 1_000_000]}
            invalid={insufficient && from ? `Hũ ${from.name} chỉ còn ${formatMoney(from.balance)}.` : null}
          />

          <div className="space-y-2">
            <Label htmlFor="transfer-note">Ghi chú</Label>
            <Input id="transfer-note" value={note} onChange={(e) => setNote(e.target.value)} className="h-11" maxLength={500} />
          </div>
        </CardContent>
      </Card>

      <SubmitButton pending={transfer.isPending} disabled={!valid}>
        Chuyển tiền giữa các hũ
      </SubmitButton>
    </form>
  );
};

// ---- page -----------------------------------------------------------------------------------------------------

const Transfer = () => {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const mode = parseMode(params.get("mode"));
  const jars = useJars();

  return (
    <div className="animate-slide-up space-y-6">
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={() => navigate("/")}
          className="flex h-9 w-9 items-center justify-center rounded-xl bg-secondary transition-colors hover:bg-secondary/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          <span className="sr-only">Quay lại</span>
        </button>
        <div>
          <h1 className="text-2xl font-display font-bold">Giao dịch mới</h1>
          <p className="text-sm text-muted-foreground">Ghi khoản chi, khoản thu hoặc chuyển tiền giữa các hũ</p>
        </div>
      </div>

      <div role="radiogroup" aria-label="Loại giao dịch" className="grid grid-cols-3 gap-1 rounded-xl bg-secondary p-1">
        {MODES.map((m) => (
          <button
            key={m.value}
            type="button"
            role="radio"
            aria-checked={mode === m.value}
            onClick={() => setParams({ mode: m.value }, { replace: true })}
            className={cn(
              "rounded-lg px-3 py-2 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              mode === m.value ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
            )}
          >
            {m.label}
          </button>
        ))}
      </div>

      {jars.isPending && (
        <div className="space-y-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-16 w-full rounded-xl" />
          ))}
        </div>
      )}
      {jars.isError && <ErrorNotice error={jars.error} what="danh sách hũ" onRetry={() => void jars.refetch()} />}

      {jars.data && (
        <AnimatePresence mode="wait">
          <motion.div key={mode} initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}>
            {mode === "expense" && <ExpenseForm jars={jars.data} />}
            {mode === "income" && <IncomeForm jars={jars.data} />}
            {mode === "jars" && <JarTransferForm jars={jars.data} />}
          </motion.div>
        </AnimatePresence>
      )}
    </div>
  );
};

export default Transfer;
