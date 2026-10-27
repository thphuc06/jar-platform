import { ArrowRightLeft, Loader2 } from "lucide-react";
import { useState } from "react";
import { useIdempotencyKey } from "@/api/idempotency";
import { useTransfer, type JarTransfer } from "@/api/queries";
import AmountField from "@/components/AmountField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { useToast } from "@/hooks/use-toast";
import { formatVND } from "@/lib/format";
import { fits, parseAmount, shareOf } from "@/lib/money";

export interface JarOption {
  id: string;
  name: string;
  balance: string;
  targetPct?: number;
}

const selectClass =
  "flex h-10 w-full rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring";

/**
 * POST /jars/transfers between two of the user's jars; "Unallocated" is the POOL jar (poolJarId from GET /jars), so
 * allocating income is just a transfer out of it. The total balance does not change.
 */
const MoveMoneyDialog = ({
  options,
  initialFrom,
  initialTo,
  onClose,
}: {
  options: JarOption[];
  initialFrom: string;
  initialTo: string;
  onClose: () => void;
}) => {
  const { toast } = useToast();
  const transfer = useTransfer();
  const idempotency = useIdempotencyKey();
  const [fromId, setFromId] = useState(initialFrom);
  const [toId, setToId] = useState(initialTo);
  const [amountText, setAmountText] = useState("");
  const [note, setNote] = useState("");

  const from = options.find((o) => o.id === fromId);
  const to = options.find((o) => o.id === toId);
  const amount = parseAmount(amountText);
  const tooMuch = amount !== null && from !== undefined && !fits(amount, from.balance);
  const ready = amount !== null && from && to && fromId !== toId && !tooMuch;
  // the target-percentage share of the money being moved out, e.g. 55 % of Unallocated for Necessities
  const suggestion = from && to?.targetPct !== undefined ? shareOf(from.balance, to.targetPct) : null;

  const save = () => {
    if (!ready || !amount) return;
    const body: JarTransfer = { fromJarId: fromId, toJarId: toId, amount, note: note.trim() || null };
    transfer.mutate(
      { key: idempotency.keyFor(body), body },
      {
        onSuccess: ({ replayed }) => {
          idempotency.done();
          toast({
            title: replayed ? "Already moved" : "Money moved",
            description: replayed
              ? "Your earlier attempt went through; nothing was moved twice."
              : `${formatVND(amount)}: ${from.name} → ${to.name}`,
          });
          onClose();
        },
        onError: (err) => toast({ title: "Not moved", description: err.message, variant: "destructive" }),
      },
    );
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 font-display">
            <ArrowRightLeft className="h-5 w-5 text-primary" /> Move money
          </DialogTitle>
          <DialogDescription>Between your own jars; your total balance stays the same.</DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="grid grid-cols-2 gap-3">
            <label className="block space-y-2 text-sm font-medium">
              <span>From</span>
              <select
                className={selectClass}
                value={fromId}
                onChange={(e) => {
                  const next = e.target.value;
                  setFromId(next);
                  // "To" never offers the "From" jar; keep its state in line with what it shows
                  if (next === toId) setToId(options.find((o) => o.id !== next)?.id ?? next);
                }}
              >
                {options.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="block space-y-2 text-sm font-medium">
              <span>To</span>
              <select className={selectClass} value={toId} onChange={(e) => setToId(e.target.value)}>
                {options
                  .filter((o) => o.id !== fromId)
                  .map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.name}
                    </option>
                  ))}
              </select>
            </label>
          </div>
          {from && <p className="text-xs text-muted-foreground">Available in {from.name}: {formatVND(from.balance)}</p>}
          <AmountField
            value={amountText}
            onChange={setAmountText}
            hint={tooMuch && from ? `${from.name} holds only ${formatVND(from.balance)}` : undefined}
          />
          {suggestion && suggestion !== "0" && to && (
            <Button type="button" variant="secondary" size="sm" className="w-full text-xs" onClick={() => setAmountText(suggestion)}>
              {to.targetPct} % target of {to.name}: {formatVND(suggestion)}
            </Button>
          )}
          <label className="block space-y-2 text-sm font-medium">
            <span>Note</span>
            <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="Optional" maxLength={500} />
          </label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={save} disabled={!ready || transfer.isPending} className="gap-1.5">
            {transfer.isPending && <Loader2 className="h-4 w-4 animate-spin" />} Move
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default MoveMoneyDialog;
