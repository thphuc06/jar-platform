import { useState } from "react";
import { ArrowRightLeft, Loader2 } from "lucide-react";
import { useIntentKey } from "@/api/idempotency";
import { useTransferBetweenJars } from "@/api/jars";
import { describeError } from "@/api/problem";
import type { Jar } from "@/api/types";
import AmountField from "@/components/AmountField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useToast } from "@/hooks/use-toast";
import { compareMoney, formatMoney, parseVndInput } from "@/lib/money";

interface Props {
  from: Jar | null;
  jars: Jar[];
  onClose: () => void;
}

/** Move money from one jar to another: POST /jars/transfers. */
const TransferJarDialog = ({ from, jars, onClose }: Props) => (
  <Dialog open={from !== null} onOpenChange={(open) => !open && onClose()}>
    <DialogContent className="sm:max-w-md">{from && <TransferForm key={from.id} from={from} jars={jars} onClose={onClose} />}</DialogContent>
  </Dialog>
);

const TransferForm = ({ from, jars, onClose }: { from: Jar; jars: Jar[]; onClose: () => void }) => {
  const { toast } = useToast();
  const transfer = useTransferBetweenJars();
  const intent = useIntentKey();
  const [toJarId, setToJarId] = useState("");
  const [amount, setAmount] = useState("");

  const value = parseVndInput(amount);
  const insufficient = value !== null && compareMoney({ amount: value, currency: "VND" }, from.balance) > 0;
  const valid = Boolean(value && toJarId && !insufficient);

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!value || !valid) return;
    const body = { fromJarId: from.id, toJarId, amount: value };
    transfer.mutate(
      { body, idempotencyKey: intent.get(JSON.stringify(body)) },
      {
        onSuccess: ({ replayed }) => {
          intent.clear();
          const to = jars.find((j) => j.id === toJarId);
          toast(
            replayed
              ? { title: "Lần chuyển này đã được ghi trước đó" }
              : { title: "Đã chuyển tiền", description: `${formatMoney({ amount: value, currency: "VND" })}: ${from.name} → ${to?.name ?? ""}` },
          );
          onClose();
        },
        onError: (error) => toast({ title: "Không chuyển được tiền", description: describeError(error), variant: "destructive" }),
      },
    );
  };

  return (
    <form onSubmit={submit} noValidate>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2 font-display">
          <ArrowRightLeft className="h-5 w-5 text-primary" aria-hidden="true" /> Chuyển từ hũ {from.name}
        </DialogTitle>
        <DialogDescription>Số dư hiện tại: {formatMoney(from.balance)}</DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        <div className="space-y-2">
          <Label htmlFor="jar-transfer-to">Chuyển đến hũ</Label>
          <Select value={toJarId} onValueChange={setToJarId}>
            <SelectTrigger id="jar-transfer-to">
              <SelectValue placeholder="Chọn hũ..." />
            </SelectTrigger>
            <SelectContent>
              {jars
                .filter((j) => j.id !== from.id)
                .map((j) => (
                  <SelectItem key={j.id} value={j.id}>
                    {j.name}
                  </SelectItem>
                ))}
            </SelectContent>
          </Select>
        </div>
        <AmountField
          id="jar-transfer-amount"
          value={amount}
          onChange={setAmount}
          quick={[100_000, 500_000, 1_000_000]}
          invalid={insufficient ? `Hũ ${from.name} chỉ còn ${formatMoney(from.balance)}.` : null}
        />
      </div>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose} disabled={transfer.isPending}>
          Hủy
        </Button>
        <Button type="submit" disabled={!valid || transfer.isPending} className="gap-1.5">
          {transfer.isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
          Chuyển tiền
        </Button>
      </DialogFooter>
    </form>
  );
};

export default TransferJarDialog;
