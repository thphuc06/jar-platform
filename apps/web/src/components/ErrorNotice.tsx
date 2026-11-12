import { AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ApiError, describeError } from "@/api/problem";

interface Props {
  error: unknown;
  /** What could not be loaded, e.g. "danh sách hũ" */
  what: string;
  onRetry?: () => void;
}

/** Inline error state of a query: what failed, why (the server's message) and a retry button. */
const ErrorNotice = ({ error, what, onRetry }: Props) => (
  <Card role="alert" className="border-destructive/30 bg-destructive/5">
    <CardContent className="flex flex-col items-center gap-3 p-6 text-center">
      <AlertTriangle className="h-6 w-6 text-destructive" aria-hidden="true" />
      <div>
        <p className="text-sm font-semibold">Không tải được {what}</p>
        <p className="mt-1 text-xs text-muted-foreground">{describeError(error)}</p>
        {error instanceof ApiError && error.traceId && (
          <p className="mt-1 text-[10px] text-muted-foreground/70">Mã truy vết: {error.traceId}</p>
        )}
      </div>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          Thử lại
        </Button>
      )}
    </CardContent>
  </Card>
);

export default ErrorNotice;
