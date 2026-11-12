import { useState } from "react";
import { Loader2 } from "lucide-react";
import { useCategories } from "@/api/categories";
import { useJars } from "@/api/jars";
import { describeError } from "@/api/problem";
import { useRecategorizeTransaction } from "@/api/transactions";
import type { Transaction } from "@/api/types";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useToast } from "@/hooks/use-toast";
import { transactionTitle } from "@/lib/transactions";

interface Props {
  tx: Transaction | null;
  onClose: () => void;
}

/** Move an expense to another category or jar, or change the category of income (PATCH /transactions/{id}). */
const RecategorizeDialog = ({ tx, onClose }: Props) => (
  <Dialog open={tx !== null} onOpenChange={(open) => !open && onClose()}>
    <DialogContent className="sm:max-w-md">{tx && <Form key={tx.id} tx={tx} onClose={onClose} />}</DialogContent>
  </Dialog>
);

const Form = ({ tx, onClose }: { tx: Transaction; onClose: () => void }) => {
  const { toast } = useToast();
  const categories = useCategories();
  const jars = useJars();
  const patch = useRecategorizeTransaction();
  const [categoryId, setCategoryId] = useState(tx.categoryId ?? "");
  const [jarId, setJarId] = useState(tx.jarId ?? "");

  const isExpense = tx.type === "EXPENSE";
  const kind = isExpense ? "EXPENSE" : "INCOME";
  const options = (categories.data ?? []).filter((c) => c.kind === kind);
  const changed = (categoryId !== "" && categoryId !== (tx.categoryId ?? "")) || (isExpense && jarId !== "" && jarId !== (tx.jarId ?? ""));

  const save = () => {
    patch.mutate(
      {
        id: tx.id,
        categoryId: categoryId !== (tx.categoryId ?? "") && categoryId !== "" ? categoryId : undefined,
        jarId: isExpense && jarId !== (tx.jarId ?? "") && jarId !== "" ? jarId : undefined,
      },
      {
        onSuccess: () => {
          toast({ title: "Đã cập nhật giao dịch" });
          onClose();
        },
        onError: (error) => toast({ title: "Không cập nhật được giao dịch", description: describeError(error), variant: "destructive" }),
      },
    );
  };

  return (
    <>
      <DialogHeader>
        <DialogTitle className="font-display">Đổi danh mục</DialogTitle>
        <DialogDescription>{transactionTitle(tx)}</DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-2">
        <div className="space-y-2">
          <Label htmlFor="recategorize-category">Danh mục</Label>
          <Select value={categoryId} onValueChange={setCategoryId}>
            <SelectTrigger id="recategorize-category" className="h-11">
              <SelectValue placeholder="Chọn danh mục..." />
            </SelectTrigger>
            <SelectContent>
              {options.map((c) => (
                <SelectItem key={c.id} value={c.id}>
                  {c.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        {isExpense && (
          <div className="space-y-2">
            <Label htmlFor="recategorize-jar">Hũ</Label>
            <Select value={jarId} onValueChange={setJarId}>
              <SelectTrigger id="recategorize-jar" className="h-11">
                <SelectValue placeholder="Chọn hũ..." />
              </SelectTrigger>
              <SelectContent>
                {(jars.data ?? []).map((j) => (
                  <SelectItem key={j.id} value={j.id}>
                    {j.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">Đổi hũ sẽ hoàn tiền về hũ cũ và trừ vào hũ mới.</p>
          </div>
        )}
      </div>
      <DialogFooter>
        <Button variant="outline" onClick={onClose} disabled={patch.isPending}>
          Hủy
        </Button>
        <Button onClick={save} disabled={!changed || patch.isPending} className="gap-1.5">
          {patch.isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
          Lưu
        </Button>
      </DialogFooter>
    </>
  );
};

export default RecategorizeDialog;
