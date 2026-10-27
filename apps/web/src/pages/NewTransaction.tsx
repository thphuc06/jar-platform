import { ArrowDownRight, ArrowLeft, ChevronRight, Loader2, Send } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useIdempotencyKey } from "@/api/idempotency";
import { useCategories, useJars, usePostTransaction, type NewTransaction as Body } from "@/api/queries";
import AmountField from "@/components/AmountField";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/hooks/use-toast";
import { formatVND } from "@/lib/format";
import { fits, parseAmount } from "@/lib/money";
import { cn } from "@/lib/utils";

const JAR_COLORS = ["hsl(215, 80%, 48%)", "hsl(160, 55%, 38%)", "hsl(270, 55%, 50%)", "hsl(40, 85%, 52%)", "hsl(340, 68%, 50%)", "hsl(190, 65%, 42%)"];
const BANKS = ["Vietcombank", "Techcombank", "MB Bank", "BIDV", "VPBank", "ACB", "Sacombank", "TPBank", "VIB", "Agribank"];
const selectClass =
  "flex h-11 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring";

type Mode = "spend" | "income";

/**
 * Records money going out of a jar (EXPENSE; the old "Transfer" screen: paying someone FROM a jar) or coming in
 * (INCOME, lands in POOL until it is allocated on the Jars screen). Each save carries one Idempotency-Key.
 */
const NewTransaction = () => {
  const navigate = useNavigate();
  const { toast } = useToast();
  const jars = useJars();
  const categories = useCategories();
  const post = usePostTransaction();
  const idempotency = useIdempotencyKey();

  const [mode, setMode] = useState<Mode>("spend");
  const [jarId, setJarId] = useState<string | null>(null);
  const [amountText, setAmountText] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [bank, setBank] = useState("");
  const [account, setAccount] = useState("");
  const [payer, setPayer] = useState("");
  const [note, setNote] = useState("");

  const jar = jars.data?.jars.find((j) => j.id === jarId);
  const amount = parseAmount(amountText);
  const overBalance = mode === "spend" && amount !== null && jar !== undefined && !fits(amount, jar.balance);
  const kind = mode === "spend" ? "EXPENSE" : "INCOME";
  const categoryOptions = (categories.data?.items ?? [])
    .filter((c) => c.kind === kind)
    // the categories meant for the chosen jar first (BR-3 default jar)
    .sort((a, b) => Number(b.defaultJarCode === jar?.code) - Number(a.defaultJarCode === jar?.code));

  const body = (): Body | null => {
    if (!amount) return null;
    const common = { amount, categoryId: categoryId || null, note: note.trim() || null };
    if (mode === "income") {
      // no jarId: income lands in POOL (BR-2)
      return { type: "INCOME", ...common, counterparty: payer.trim() || null };
    }
    if (!jarId || !bank || !account.trim()) return null;
    return { type: "EXPENSE", ...common, jarId, counterparty: `${account.trim()} (${bank})` };
  };
  const ready = body() !== null && !overBalance;

  const save = () => {
    const request = body();
    if (!request) return;
    post.mutate(
      { key: idempotency.keyFor(request), body: request },
      {
        onSuccess: ({ replayed }) => {
          idempotency.done();
          toast({
            title: replayed ? "Already recorded" : mode === "spend" ? "Payment recorded" : "Income recorded",
            description: replayed
              ? "This was saved by your earlier attempt; it was not recorded twice."
              : `${formatVND(request.amount)}${mode === "income" ? " is waiting in Unallocated" : ""}`,
          });
          navigate(mode === "income" ? "/jars" : "/", { replace: true });
        },
        onError: (err) =>
          // the key is kept: pressing the button again with the same details is a safe retry of the same action
          toast({ title: "Not recorded", description: err.message, variant: "destructive" }),
      },
    );
  };

  const switchMode = (next: Mode) => {
    setMode(next);
    setCategoryId("");
  };

  const pickingJar = mode === "spend" && !jar;

  return (
    <div className="space-y-6 animate-slide-up">
      <div className="flex items-center gap-3">
        <button
          type="button"
          aria-label="Back"
          onClick={() => (mode === "spend" && jar ? setJarId(null) : navigate("/"))}
          className="h-9 w-9 rounded-xl bg-secondary flex items-center justify-center hover:bg-secondary/80 transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
        </button>
        <div>
          <h1 className="text-2xl font-display font-bold">{mode === "spend" ? "Spend" : "Income"}</h1>
          <p className="text-muted-foreground text-sm">
            {mode === "income" ? "Money coming in goes to Unallocated first" : jar ? `From ${jar.name}` : "Pick the jar that pays"}
          </p>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-2 rounded-xl bg-secondary p-1" role="group" aria-label="Kind">
        {(["spend", "income"] as const).map((m) => (
          <button
            key={m}
            type="button"
            aria-pressed={mode === m}
            onClick={() => switchMode(m)}
            className={cn("rounded-lg py-2 text-sm font-medium transition-colors", mode === m ? "bg-background shadow-sm" : "text-muted-foreground")}
          >
            {m === "spend" ? "Spend from a jar" : "Record income"}
          </button>
        ))}
      </div>

      {pickingJar && (
        <div className="space-y-2">
          {jars.isLoading && Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-16 w-full rounded-xl" />)}
          {jars.data?.jars.map((j, i) => (
            <Card key={j.id} className="cursor-pointer hover:shadow-md transition-all overflow-hidden group" onClick={() => setJarId(j.id)}>
              <div className="h-1" style={{ backgroundColor: JAR_COLORS[i % JAR_COLORS.length] }} />
              <CardContent className="p-4 flex items-center justify-between">
                <div>
                  <p className="font-semibold text-sm">{j.name}</p>
                  <p className="text-xs text-muted-foreground">Available: {formatVND(j.balance)}</p>
                </div>
                <ChevronRight className="h-4 w-4 text-muted-foreground group-hover:translate-x-0.5 transition-transform" />
              </CardContent>
            </Card>
          ))}
          {jars.data && jars.data.jars.every((j) => j.balance === "0.00") && (
            <p className="text-sm text-muted-foreground">
              Your jars are empty. <Link to="/jars" className="text-primary underline">Allocate income</Link> first.
            </p>
          )}
        </div>
      )}

      {!pickingJar && (
        <>
          <Card>
            <CardContent className="p-5 space-y-4">
              {mode === "spend" ? (
                <>
                  <label className="block space-y-2 text-sm font-medium">
                    <span>Recipient bank</span>
                    <select className={selectClass} value={bank} onChange={(e) => setBank(e.target.value)}>
                      <option value="">Select bank...</option>
                      {BANKS.map((b) => (
                        <option key={b}>{b}</option>
                      ))}
                    </select>
                  </label>
                  <label className="block space-y-2 text-sm font-medium">
                    <span>Account number</span>
                    <Input value={account} onChange={(e) => setAccount(e.target.value)} className="h-11" maxLength={60} />
                  </label>
                </>
              ) : (
                <label className="block space-y-2 text-sm font-medium">
                  <span>From</span>
                  <Input value={payer} onChange={(e) => setPayer(e.target.value)} placeholder="e.g. employer" className="h-11" maxLength={200} />
                </label>
              )}
              <AmountField
                value={amountText}
                onChange={setAmountText}
                quick={mode === "spend" ? [500_000, 1_000_000, 5_000_000] : [5_000_000, 10_000_000, 20_000_000]}
                hint={overBalance && jar ? `${jar.name} holds only ${formatVND(jar.balance)}` : undefined}
              />
              <label className="block space-y-2 text-sm font-medium">
                <span>Category</span>
                <select className={selectClass} value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
                  <option value="">No category</option>
                  {categoryOptions.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block space-y-2 text-sm font-medium">
                <span>Note</span>
                <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Optional" className="h-11" maxLength={500} />
              </label>
            </CardContent>
          </Card>

          <Button className="w-full h-12 gap-2 gradient-premium border-0 shadow-lg shadow-primary/20 text-base" disabled={!ready || post.isPending} onClick={save}>
            {post.isPending ? (
              <>
                <Loader2 className="h-5 w-5 animate-spin" /> Saving...
              </>
            ) : mode === "spend" ? (
              <>
                <Send className="h-5 w-5" /> Pay
              </>
            ) : (
              <>
                <ArrowDownRight className="h-5 w-5" /> Record income
              </>
            )}
          </Button>
        </>
      )}
    </div>
  );
};

export default NewTransaction;
