import { useState } from "react";
import { Loader2, Plus } from "lucide-react";
import { useCreateJar } from "@/api/jars";
import { describeError } from "@/api/problem";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useToast } from "@/hooks/use-toast";
import { formatPct, parsePct, sumPct } from "@/lib/percent";

interface Props {
  open: boolean;
  onClose: () => void;
  /** Sum of the shares of the existing jars, in percent */
  usedPct: number;
}

/** Create a custom jar with a share of income (POST /jars). The shares of all jars may not exceed 100 %. */
const NewJarDialog = ({ open, onClose, usedPct }: Props) => (
  <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
    <DialogContent className="sm:max-w-md">{open && <NewJarForm usedPct={usedPct} onClose={onClose} />}</DialogContent>
  </Dialog>
);

const NewJarForm = ({ usedPct, onClose }: { usedPct: number; onClose: () => void }) => {
  const { toast } = useToast();
  const create = useCreateJar();
  const [name, setName] = useState("");
  const [pctText, setPctText] = useState("0");

  const pct = parsePct(pctText);
  const freePct = sumPct([100, -usedPct]);
  const tooMuch = pct !== null && sumPct([usedPct, pct]) > 100;
  const valid = name.trim() !== "" && pct !== null && !tooMuch;

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!valid || pct === null) return;
    create.mutate(
      { name: name.trim(), targetPct: pct },
      {
        onSuccess: (jar) => {
          toast({ title: "Đã tạo hũ mới", description: jar.name });
          onClose();
        },
        onError: (error) => toast({ title: "Không tạo được hũ", description: describeError(error), variant: "destructive" }),
      },
    );
  };

  return (
    <form onSubmit={submit} noValidate>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2 font-display">
          <Plus className="h-5 w-5 text-primary" aria-hidden="true" /> Tạo hũ mới
        </DialogTitle>
        <DialogDescription>Tỷ lệ còn có thể phân bổ: {formatPct(freePct)}</DialogDescription>
      </DialogHeader>
      <div className="space-y-4 py-4">
        <div className="space-y-2">
          <Label htmlFor="new-jar-name">Tên hũ</Label>
          <Input id="new-jar-name" placeholder="Ví dụ: Du lịch, Sức khỏe..." value={name} onChange={(e) => setName(e.target.value)} maxLength={40} autoFocus />
        </div>
        <div className="space-y-2">
          <Label htmlFor="new-jar-pct">Tỷ lệ thu nhập (%)</Label>
          <Input
            id="new-jar-pct"
            inputMode="decimal"
            value={pctText}
            onChange={(e) => setPctText(e.target.value)}
            aria-invalid={pct === null || tooMuch ? true : undefined}
            aria-describedby="new-jar-pct-help"
          />
          <p id="new-jar-pct-help" role={tooMuch || pct === null ? "alert" : undefined} className={tooMuch || pct === null ? "text-xs text-destructive" : "text-xs text-muted-foreground"}>
            {pct === null
              ? "Nhập số từ 0 đến 100 (tối đa 2 chữ số thập phân)."
              : tooMuch
                ? `Tổng tỷ lệ các hũ không được vượt quá 100% (còn ${formatPct(freePct)}).`
                : "Phần trăm thu nhập sẽ chia vào hũ này khi phân bổ thu nhập."}
          </p>
        </div>
      </div>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onClose} disabled={create.isPending}>
          Hủy
        </Button>
        <Button type="submit" disabled={!valid || create.isPending} className="gap-1.5 border-0 gradient-premium">
          {create.isPending ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <Plus className="h-4 w-4" aria-hidden="true" />}
          Tạo hũ
        </Button>
      </DialogFooter>
    </form>
  );
};

export default NewJarDialog;
