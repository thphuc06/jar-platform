import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { formatVND } from "@/lib/format";
import { parseAmount } from "@/lib/money";

/**
 * Whole-VND input. The text stays a string end to end (typed → parseAmount → JSON), never a JS number, so even the
 * 17-digit amounts the API allows arrive exactly. inputMode="numeric" opens the number pad on phones.
 */
const AmountField = ({
  value,
  onChange,
  quick = [],
  label = "Amount (VND)",
  hint,
}: {
  value: string;
  onChange: (text: string) => void;
  quick?: number[];
  label?: string;
  hint?: string;
}) => {
  const amount = parseAmount(value);
  return (
    <div className="space-y-2">
      <label className="block space-y-2 text-sm font-medium">
        <span>{label}</span>
        <Input
          inputMode="numeric"
          autoComplete="off"
          placeholder="0"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="h-11 text-lg font-semibold"
        />
      </label>
      {value !== "" && (
        <p className={amount ? "text-xs text-muted-foreground" : "text-xs text-destructive"}>
          {amount ? formatVND(amount) : "Enter a whole amount of VND"}
        </p>
      )}
      {hint && <p className="text-xs text-destructive">{hint}</p>}
      {quick.length > 0 && (
        <div className="flex gap-2">
          {quick.map((v) => (
            <Button key={v} type="button" variant="secondary" size="sm" className="flex-1 text-xs" onClick={() => onChange(String(v))}>
              {formatVND(v)}
            </Button>
          ))}
        </div>
      )}
    </div>
  );
};

export default AmountField;
