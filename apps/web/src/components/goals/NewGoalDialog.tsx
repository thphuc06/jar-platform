import { useState } from "react";
import { Loader2, Plus, Target } from "lucide-react";
import { useCreateGoal } from "@/api/goals";
import { useJars } from "@/api/jars";
import { describeError } from "@/api/problem";
import AmountField from "@/components/AmountField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useToast } from "@/hooks/use-toast";
import { addMonthsIso } from "@/lib/dates";
import { formatMoney, parseVndInput } from "@/lib/money";

const NO_JAR = "NONE";

interface Props {
  open: boolean;
  onClose: () => void;
}

const NewGoalDialog = ({ open, onClose }: Props) => (
  <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
    <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-md">{open && <NewGoalForm onClose={onClose} />}</DialogContent>
  </Dialog>
);

const NewGoalForm = ({ onClose }: { onClose: () => void }) => {
  const { toast } = useToast();
  const create = useCreateGoal();
  const jars = useJars();
  const [name, setName] = useState("");
  const [amount, setAmount] = useState("");
  const [monthsText, setMonthsText] = useState("12");
  const [jarId, setJarId] = useState(NO_JAR);

  const value = parseVndInput(amount);
  const months = Number(monthsText);
  const monthsValid = Number.isInteger(months) && months >= 1 && months <= 600;
  const valid = name.trim() !== "" && value !== null && monthsValid;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!valid || !value) return;
    create.mutate(
      {
        name: name.trim(),
        targetAmount: value,
        targetDate: addMonthsIso(new Date(), months),
        fundingJarId: jarId === NO_JAR ? undefined : jarId,
      },
      {
        onSuccess: () => {
          toast({ title: "Đã tạo mục tiêu mới", description: `${name.trim()} — ${formatMoney({ amount: value, currency: "VND" })}` });
          onClose();
        },
        onError: (error) => toast({ title: "Không tạo được mục tiêu", description: describeError(error), variant: "destructive" }),
      },
    );
  };

  return (
    <form onSubmit={submit} noValidate>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2 font-display">
          <Target className="h-5 w-5 text-primary" aria-hidden="true" /> Tạo mục tiêu mới
        </DialogTitle>
        <DialogDescription>Đặt tên, số tiền và thời hạn</DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        <div className="space-y-2">
          <Label htmlFor="goal-name">Tên mục tiêu</Label>
          <Input id="goal-name" placeholder="Ví dụ: Mua nhà, Du lịch..." value={name} onChange={(e) => setName(e.target.value)} maxLength={80} autoFocus />
        </div>
        <AmountField id="goal-amount" label="Số tiền cần đạt (VND)" value={amount} onChange={setAmount} quick={[10_000_000, 50_000_000, 100_000_000]} />
        <div className="space-y-2">
          <Label htmlFor="goal-months">Thời hạn (tháng)</Label>
          <Input
            id="goal-months"
            type="number"
            min={1}
            max={600}
            inputMode="numeric"
            value={monthsText}
            onChange={(e) => setMonthsText(e.target.value)}
            aria-invalid={monthsValid ? undefined : true}
          />
          {!monthsValid && (
            <p role="alert" className="text-xs text-destructive">
              Nhập số tháng từ 1 đến 600.
            </p>
          )}
        </div>
        <div className="space-y-2">
          <Label htmlFor="goal-jar">Hũ theo dõi tiến độ</Label>
          <Select value={jarId} onValueChange={setJarId}>
            <SelectTrigger id="goal-jar">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NO_JAR}>Không gắn với hũ nào</SelectItem>
              {(jars.data ?? []).map((j) => (
                <SelectItem key={j.id} value={j.id}>
                  {j.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">Số dư của hũ này được tính là tiền đã tiết kiệm cho mục tiêu.</p>
        </div>
      </div>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose} disabled={create.isPending}>
          Hủy
        </Button>
        <Button type="submit" disabled={!valid || create.isPending} className="gap-1.5 border-0 gradient-premium">
          {create.isPending ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <Plus className="h-4 w-4" aria-hidden="true" />}
          Tạo mục tiêu
        </Button>
      </DialogFooter>
    </form>
  );
};

export default NewGoalDialog;
