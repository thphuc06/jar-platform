import { Loader2 } from "lucide-react";
import { useState } from "react";
import { ApiError } from "@/api";
import { useAmendTransaction, useCategories, useJars, type Amendment } from "@/api/queries";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { useToast } from "@/hooks/use-toast";
import { formatDateTime, formatVND } from "@/lib/format";
import { describe, type TransactionItem } from "@/lib/transactions";

const NO_CATEGORY = "";
// native <select>: the phone's own picker on mobile (this is a PWA), and nothing to emulate in tests
const selectClass =
  "flex h-10 w-full rounded-md border border-input bg-background px-3 text-sm ring-offset-background " +
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring";

/**
 * Edits category, jar and note of a transaction. Only the fields the user actually changed are sent (merge patch),
 * so a concurrent change of another field by someone else is not overwritten. Amount and type are read-only (BR-2);
 * a jar transfer only allows its note to change.
 */
const AmendTransactionDialog = ({ tx, onClose }: { tx: TransactionItem; onClose: () => void }) => {
  const { toast } = useToast();
  const categories = useCategories();
  const jars = useJars();
  const amend = useAmendTransaction();

  const isTransfer = tx.type === "JAR_TRANSFER";
  const [categoryId, setCategoryId] = useState(tx.category?.id ?? NO_CATEGORY);
  const [jarId, setJarId] = useState(tx.jar.id);
  const [note, setNote] = useState(tx.note ?? "");

  // an expense is booked against an expense category, an income against an income category (category-kind-mismatch)
  const kind = tx.type === "INCOME" ? "INCOME" : "EXPENSE";
  const categoryOptions = (categories.data?.items ?? []).filter((c) => c.kind === kind);
  // GET /jars hides POOL; an income still sitting in POOL keeps it as an option ("Unallocated")
  const jarOptions = [
    ...(tx.jar.code === "POOL" ? [{ id: tx.jar.id, name: "Unallocated" }] : []),
    ...(jars.data?.jars ?? []).map((j) => ({ id: j.id, name: j.name })),
  ];

  const changes: Amendment = {};
  if (!isTransfer && categoryId !== (tx.category?.id ?? NO_CATEGORY)) {
    changes.categoryId = categoryId === NO_CATEGORY ? null : categoryId;
  }
  if (!isTransfer && jarId !== tx.jar.id) {
    changes.jarId = jarId;
  }
  if (note.trim() !== (tx.note ?? "")) {
    changes.note = note.trim() === "" ? null : note.trim();
  }
  const changed = Object.keys(changes).length > 0;

  const save = () =>
    amend.mutate(
      { id: tx.id, changes },
      {
        onSuccess: () => {
          toast({ title: "Transaction updated" });
          onClose();
        },
        onError: (err) =>
          toast({
            title: "Could not update the transaction",
            description: err instanceof ApiError && err.problem?.detail ? err.problem.detail : err.message,
            variant: "destructive",
          }),
      },
    );

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="font-display">{describe(tx).title}</DialogTitle>
          <DialogDescription>
            {formatVND(tx.amount)} · {formatDateTime(tx.occurredAt)}
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-2">
          {!isTransfer && (
            <>
              <label className="block space-y-2 text-sm font-medium">
                <span>Category</span>
                <select className={selectClass} value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
                  <option value={NO_CATEGORY}>No category</option>
                  {categoryOptions.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block space-y-2 text-sm font-medium">
                <span>{tx.type === "INCOME" ? "Into jar" : "From jar"}</span>
                <select className={selectClass} value={jarId} onChange={(e) => setJarId(e.target.value)}>
                  {jarOptions.map((j) => (
                    <option key={j.id} value={j.id}>
                      {j.name}
                    </option>
                  ))}
                </select>
              </label>
            </>
          )}
          <label className="block space-y-2 text-sm font-medium">
            <span>Note</span>
            <Input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} placeholder="Optional" />
          </label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={save} disabled={!changed || amend.isPending} className="gap-1.5">
            {amend.isPending && <Loader2 className="h-4 w-4 animate-spin" />} Save
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AmendTransactionDialog;
