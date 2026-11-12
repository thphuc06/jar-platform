import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import MoneyInput from "@/components/MoneyInput";
import { formatAmount } from "@/lib/money";

interface Props {
  id: string;
  label?: string;
  /** Digits only */
  value: string;
  onChange: (digits: string) => void;
  /** Preset amounts in whole VND, shown as buttons under the field */
  quick?: readonly number[];
  invalid?: string | null;
}

/** Labelled amount field with a formatted preview and quick-pick buttons. */
const AmountField = ({ id, label = "Số tiền (VND)", value, onChange, quick = [], invalid }: Props) => (
  <div className="space-y-2">
    <Label htmlFor={id}>{label}</Label>
    <MoneyInput
      id={id}
      value={value}
      onValueChange={onChange}
      aria-invalid={invalid ? true : undefined}
      aria-describedby={invalid ? `${id}-error` : undefined}
      className="h-11 text-lg font-semibold"
    />
    {invalid ? (
      <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
        {invalid}
      </p>
    ) : (
      value !== "" && <p className="text-xs text-muted-foreground">{formatAmount(value)}</p>
    )}
    {quick.length > 0 && (
      <div className="flex gap-2">
        {quick.map((v) => (
          <Button key={v} type="button" variant="secondary" size="sm" className="flex-1 text-xs" onClick={() => onChange(String(v))}>
            {formatAmount(String(v))}
          </Button>
        ))}
      </div>
    )}
  </div>
);

export default AmountField;
