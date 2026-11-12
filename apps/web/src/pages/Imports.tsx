import { useEffect, useRef, useState } from "react";
import { CheckCircle2, FileUp, Loader2, Upload, XCircle } from "lucide-react";
import { Link } from "react-router-dom";
import {
  isFinished,
  useImport,
  useImportFinished,
  useImportHistory,
  useUploadStatement,
  type ImportStatus,
  type StatementImport,
} from "@/api/imports";
import { describeError } from "@/api/problem";
import ErrorNotice from "@/components/ErrorNotice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDate } from "@/lib/dates";

const STATUS_LABEL: Record<ImportStatus, string> = {
  AWAITING_UPLOAD: "Chờ tệp",
  PROCESSING: "Đang nhập",
  COMPLETED: "Đã nhập",
  FAILED: "Thất bại",
};

const statusVariant = (status: ImportStatus) =>
  status === "COMPLETED" ? ("secondary" as const) : status === "FAILED" ? ("destructive" as const) : ("outline" as const);

/** The outcome of one import: counts, the reason of a failure, and the rows that were skipped. */
export const ImportResult = ({ result }: { result: StatementImport }) => {
  if (!isFinished(result.status)) {
    return (
      <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> Đang đọc và ghi từng dòng của {result.fileName}…
      </p>
    );
  }
  if (result.status === "FAILED") {
    return (
      <div className="flex items-start gap-2 text-sm" role="alert">
        <XCircle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" aria-hidden="true" />
        <div>
          <p className="font-semibold">Không nhập được {result.fileName}</p>
          <p className="text-muted-foreground">{result.failureReason}</p>
        </div>
      </div>
    );
  }
  return (
    <div className="space-y-3 text-sm" role="status">
      <div className="flex items-start gap-2">
        <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-success" aria-hidden="true" />
        <div>
          <p className="font-semibold">Đã ghi {result.postedRows ?? 0} giao dịch vào sổ</p>
          <p className="text-muted-foreground">
            {result.skippedRows ? `${result.skippedRows} dòng không nhập được.` : "Không có dòng nào bị bỏ qua."}{" "}
            <Link to="/inbox" className="underline">
              Xem trong Hộp tiền
            </Link>
          </p>
        </div>
      </div>
      {result.errors.length > 0 && (
        <ul className="max-h-60 space-y-1 overflow-y-auto rounded-md border p-2 text-xs" aria-label="Các dòng bị bỏ qua">
          {result.errors.map((e) => (
            <li key={e.line}>
              <span className="font-mono text-muted-foreground">Dòng {e.line}:</span> {e.reason}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};

const Imports = () => {
  const fileInput = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [currentId, setCurrentId] = useState<string>();
  const upload = useUploadStatement();
  const current = useImport(currentId);
  const history = useImportHistory();
  const onFinished = useImportFinished();
  const finishedOnce = useRef<string>();

  // the import's postings change balances, the inbox and the dashboard: refresh them once it is done
  useEffect(() => {
    const data = current.data;
    if (data && isFinished(data.status) && finishedOnce.current !== data.id) {
      finishedOnce.current = data.id;
      void onFinished();
    }
  }, [current.data, onFinished]);

  const start = () => {
    if (!file) return;
    upload.mutate(file, { onSuccess: (created) => setCurrentId(created.statementImport.id) });
  };

  return (
    <div className="animate-slide-up space-y-5 pb-32 md:pb-6">
      <div>
        <h1 className="text-2xl font-display font-bold">Nhập sao kê</h1>
        <p className="text-sm text-muted-foreground">
          Tải tệp CSV xuất từ ngân hàng; mỗi dòng thành một giao dịch, hũ và danh mục được đoán theo nội dung.
        </p>
      </div>

      <Card>
        <CardContent className="space-y-4 p-4">
          <div className="text-xs text-muted-foreground">
            Cần một dòng tiêu đề có cột ngày (<code>date</code>, <code>Ngày giao dịch</code>…) và số tiền (
            <code>amount</code>, <code>Số tiền</code>, hoặc cặp <code>Ghi nợ</code>/<code>Ghi có</code>). Tối đa 5 MB, mã
            hoá UTF-8. Cùng một tệp không nhập được hai lần.
          </div>
          <input
            ref={fileInput}
            type="file"
            accept=".csv,.tsv,.txt,text/csv"
            aria-label="Chọn tệp sao kê"
            className="hidden"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
          <div className="flex flex-wrap items-center gap-3">
            <Button variant="outline" className="gap-2" onClick={() => fileInput.current?.click()}>
              <FileUp className="h-4 w-4" aria-hidden="true" /> Chọn tệp
            </Button>
            <span className="text-sm">{file ? file.name : "Chưa chọn tệp"}</span>
          </div>
          <Button className="w-full gap-2" disabled={!file || upload.isPending} onClick={start}>
            {upload.isPending ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <Upload className="h-4 w-4" aria-hidden="true" />}
            Nhập sao kê
          </Button>
          {upload.isError && (
            <p className="text-sm text-destructive" role="alert">
              {describeError(upload.error)}
            </p>
          )}
          {current.data && <ImportResult result={current.data} />}
        </CardContent>
      </Card>

      <section className="space-y-2">
        <h2 className="text-sm font-semibold text-muted-foreground">Lịch sử nhập</h2>
        {history.isPending ? (
          <Skeleton className="h-16 w-full" />
        ) : history.isError ? (
          <ErrorNotice error={history.error} what="lịch sử nhập" onRetry={() => void history.refetch()} />
        ) : history.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">Chưa nhập sao kê nào.</p>
        ) : (
          <ul className="space-y-2">
            {history.data.map((i) => (
              <li key={i.id}>
                <Card>
                  <CardContent className="flex items-center justify-between gap-3 p-3 text-sm">
                    <button type="button" className="min-w-0 text-left" onClick={() => setCurrentId(i.id)}>
                      <p className="truncate font-medium">{i.fileName}</p>
                      <p className="text-xs text-muted-foreground">
                        {formatDate(i.createdAt)}
                        {i.status === "COMPLETED" && ` · ${i.postedRows ?? 0} giao dịch`}
                        {i.skippedRows ? ` · ${i.skippedRows} dòng lỗi` : ""}
                      </p>
                    </button>
                    <Badge variant={statusVariant(i.status)}>{STATUS_LABEL[i.status]}</Badge>
                  </CardContent>
                </Card>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
};

export default Imports;
