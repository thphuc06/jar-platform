import { useState } from "react";
import { Coins, Loader2, Sparkles } from "lucide-react";
import { useAllocationSuggestion, useApplyAllocation, type ApplyAllocationBody } from "@/api/budget";
import { useIntentKey } from "@/api/idempotency";
import { describeError } from "@/api/problem";
import type { Jar } from "@/api/types";
import AmountField from "@/components/AmountField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { useToast } from "@/hooks/use-toast";
import { formatAmount, formatMoney, parseVndInput } from "@/lib/money";
import { formatPct, isFullAllocation, parsePct, splitByPercent, sumPct } from "@/lib/percent";
import { jarColor } from "@/lib/jars";

interface Props {
  open: boolean;
  jars: Jar[];
  onClose: () => void;
}

/**
 * Split an income over the jars. The server suggests shares (POST /budget/allocation-suggestions), the user can edit
 * them as percentages, and applying books the income and the split in one request (POST /budget/allocations,
 * Idempotency-Key). The shares must add up to exactly 100 %. The server wants **amounts**, not percentages, and the
 * amounts must add up to the income to the dong, so the percentages are converted with `splitByPercent`.
 */
const AllocateIncomeDialog = ({ open, jars, onClose }: Props) => (
  <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
    <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-md">{open && <AllocateForm jars={jars} onClose={onClose} />}</DialogContent>
  </Dialog>
);

const AllocateForm = ({ jars, onClose }: { jars: Jar[]; onClose: () => void }) => {
  const { toast } = useToast();
  const suggest = useAllocationSuggestion();
  const apply = useApplyAllocation();
  const intent = useIntentKey();
  const [amount, setAmount] = useState("");
  // percentage text per jar id; null until a suggestion (or the current shares) has been loaded
  const [lines, setLines] = useState<Record<string, string> | null>(null);

  const value = parseVndInput(amount);
  const parsed = lines ? jars.map((j) => parsePct(lines[j.id] ?? "0")) : [];
  const allValid = parsed.length > 0 && parsed.every((p) => p !== null);
  const total = sumPct(parsed.map((p) => p ?? 0));
  const full = allValid && isFullAllocation(parsed as number[]);
  const canApply = Boolean(value && lines && full);
  // the exact amount per jar (same order as `jars`) once the shares are valid; they are what will be booked
  const exact = value && lines && full ? splitByPercent(value, parsed as number[]) : null;

  const askSuggestion = () => {
    if (!value) return;
    suggest.mutate(
      { amount: value },
      {
        onSuccess: (suggestion) =>
          setLines(Object.fromEntries(jars.map((j) => [j.id, String(suggestion.lines.find((l) => l.jarId === j.id)?.sharePct ?? 0)]))),
        onError: (error) => toast({ title: "Không lấy được gợi ý phân bổ", description: describeError(error), variant: "destructive" }),
      },
    );
  };

  const fillCurrentShares = () => setLines(Object.fromEntries(jars.map((j) => [j.id, String(j.targetPct)])));

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!value || !exact) return;
    const body: ApplyAllocationBody = {
      amount: value,
      // a jar with a 0 % share gets no split (the server wants every amount above zero)
      splits: jars.map((j, i) => ({ jarId: j.id, amount: exact[i] })).filter((split) => split.amount !== "0"),
    };
    apply.mutate(
      { body, idempotencyKey: intent.get(JSON.stringify(body)) },
      {
        onSuccess: ({ replayed }) => {
          intent.clear();
          toast(
            replayed
              ? { title: "Lần phân bổ này đã được ghi trước đó" }
              : { title: "Đã phân bổ thu nhập", description: `${formatMoney({ amount: value, currency: "VND" })} đã chia vào ${body.splits?.length ?? 0} hũ` },
          );
          onClose();
        },
        onError: (error) => toast({ title: "Không phân bổ được thu nhập", description: describeError(error), variant: "destructive" }),
      },
    );
  };

  return (
    <form onSubmit={submit} noValidate>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2 font-display">
          <Coins className="h-5 w-5 text-primary" aria-hidden="true" /> Phân bổ thu nhập
        </DialogTitle>
        <DialogDescription>Chia một khoản thu nhập vào các hũ theo tỷ lệ. Tổng các tỷ lệ phải đúng 100%.</DialogDescription>
      </DialogHeader>

      <div className="space-y-4 py-4">
        <AmountField id="allocate-amount" label="Khoản thu nhập (VND)" value={amount} onChange={setAmount} quick={[10_000_000, 20_000_000, 30_000_000]} />

        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="secondary" size="sm" className="gap-1.5" onClick={askSuggestion} disabled={!value || suggest.isPending}>
            {suggest.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" /> : <Sparkles className="h-3.5 w-3.5" aria-hidden="true" />}
            Gợi ý phân bổ
          </Button>
          <Button type="button" variant="ghost" size="sm" onClick={fillCurrentShares}>
            Dùng tỷ lệ hiện tại của các hũ
          </Button>
        </div>

        {lines && (
          <fieldset className="space-y-2">
            <legend className="mb-1 text-sm font-medium">Tỷ lệ cho từng hũ</legend>
            {jars.map((jar, i) => {
              const pct = parsePct(lines[jar.id] ?? "0");
              return (
                <div key={jar.id} className="flex items-center gap-3">
                  <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ backgroundColor: jarColor(jar, i) }} aria-hidden="true" />
                  <label htmlFor={`allocate-${jar.id}`} className="min-w-0 flex-1 truncate text-sm">
                    {jar.name}
                  </label>
                  <span className="w-28 text-right text-xs tabular-nums text-muted-foreground">
                    {exact ? formatAmount(Number(exact[i])) : value && pct !== null ? `~${formatAmount(Math.round((Number(value) * pct) / 100))}` : ""}
                  </span>
                  <div className="relative w-20">
                    <Input
                      id={`allocate-${jar.id}`}
                      inputMode="decimal"
                      value={lines[jar.id] ?? "0"}
                      onChange={(e) => setLines({ ...lines, [jar.id]: e.target.value })}
                      aria-invalid={pct === null ? true : undefined}
                      className="h-8 pr-6 text-right text-sm tabular-nums"
                    />
                    <span className="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">%</span>
                  </div>
                </div>
              );
            })}
            <p role="status" className={`pt-1 text-xs ${full ? "text-success" : "text-destructive"}`}>
              Tổng: {formatPct(total)} {full ? "(đủ 100%)" : "(cần đúng 100%)"}
            </p>
          </fieldset>
        )}
      </div>

      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose} disabled={apply.isPending}>
          Hủy
        </Button>
        <Button type="submit" disabled={!canApply || apply.isPending} className="gap-1.5 border-0 gradient-premium">
          {apply.isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
          Áp dụng phân bổ
        </Button>
      </DialogFooter>
    </form>
  );
};

export default AllocateIncomeDialog;
