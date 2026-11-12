import { forwardRef, type ComponentProps } from "react";
import { Input } from "@/components/ui/input";
import { formatGrouped, sanitizeDigits } from "@/lib/money";
import { cn } from "@/lib/utils";

interface Props extends Omit<ComponentProps<"input">, "value" | "onChange" | "type" | "inputMode"> {
  /** Digits only, e.g. "1500000" */
  value: string;
  onValueChange: (digits: string) => void;
}

/**
 * Whole-VND amount field. The user types digits, the field shows them grouped ("1.500.000"); the value that goes to the
 * form state is the plain digit string, which is also what the API wants (a decimal string, never a JS number).
 */
const MoneyInput = forwardRef<HTMLInputElement, Props>(({ value, onValueChange, className, ...props }, ref) => (
  <Input
    ref={ref}
    inputMode="numeric"
    autoComplete="off"
    placeholder="0"
    value={formatGrouped(value)}
    onChange={(event) => onValueChange(sanitizeDigits(event.target.value))}
    className={cn("tabular-nums", className)}
    {...props}
  />
));
MoneyInput.displayName = "MoneyInput";

export default MoneyInput;
