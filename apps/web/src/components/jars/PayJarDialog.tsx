import { useState } from "react";
import { Loader2, Wallet } from "lucide-react";
import { useIntentKey } from "@/api/idempotency";
import { describeError } from "@/api/problem";
import { usePostTransaction } from "@/api/transactions";
import type { Jar, PostTransactionBody } from "@/api/types";
import AmountField from "@/components/AmountField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useToast } from "@/hooks/use-toast";
import { compareMoney, formatMoney, parseVndInput } from "@/lib/money";

interface Props {
  jar: Jar | null;
  onClose: () => void;
}

/** Spend from one jar: POST /transactions with type EXPENSE. */
const PayJarDialog = ({ jar, onClose }: Props) => (
  <Dialog open={jar !== null} onOpenChange={(open) => !open && onClose()}>
    <DialogContent className="sm:max-w-md">{jar && <PayForm key={jar.id} jar={jar} onClose={onClose} />}</DialogContent>
  </Dialog>
);

const PayForm = ({ jar, onClose }: { jar: Jar; onClose: () => void }) => {
  const { toast } = useToast();
  const post = usePostTransaction();
  const intent = useIntentKey();
  const [recipient, setRecipient] = useState("");
  const [amount, setAmount] = useState("");

  const value = parseVndInput(amount);
  const valid = Boolean(value && recipient.trim());
  const over = value !== null && compareMoney({ amount: value, currency: "VND" }, jar.balance) > 0;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!value || !valid) return;
    const body: PostTransactionBody = { type: "EXPENSE", amount: value, jarId: jar.id, counterparty: recipient.trim().slice(0, 120) };
    post.mutate(
      { body, idempotencyKey: intent.get(JSON.stringify(body)) },
      {
        onSuccess: ({ replayed }) => {
          intent.clear();
          toast(
            replayed
              ? { title: "Khoản chi này đã được ghi trước đó" }
              : { title: "Đã ghi khoản chi", description: `${formatMoney({ amount: value, currency: "VND" })} từ hũ ${jar.name} cho ${body.counterparty}` },
          );
          onClose();
        },
        onError: (error) => toast({ title: "Không ghi được khoản chi", description: describeError(error), variant: "destructive" }),
      },
    );
  };

  return (
    <form onSubmit={submit} noValidate>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2 font-display">
          <Wallet className="h-5 w-5 text-primary" aria-hidden="true" /> Chi từ hũ {jar.name}
        </DialogTitle>
        <DialogDescription>Số dư hiện tại: {formatMoney(jar.balance)}</DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        <div className="space-y-2">
          <Label htmlFor="pay-recipient">Người nhận</Label>
          <Input id="pay-recipient" placeholder="Số tài khoản hoặc tên..." value={recipient} onChange={(e) => setRecipient(e.target.value)} maxLength={120} autoFocus />
        </div>
        <AmountField
          id="pay-amount"
          value={amount}
          onChange={setAmount}
          quick={[100_000, 500_000, 1_000_000]}
          invalid={over ? "Vượt số dư của hũ. Khoản chi vẫn được ghi và làm số dư hũ âm." : null}
        />
      </div>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose} disabled={post.isPending}>
          Hủy
        </Button>
        <Button type="submit" disabled={!valid || post.isPending} className="gap-1.5">
          {post.isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
          Ghi khoản chi
        </Button>
      </DialogFooter>
    </form>
  );
};

export default PayJarDialog;
