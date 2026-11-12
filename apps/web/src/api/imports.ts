import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getAccessToken } from "@/auth/session";
import { api } from "./client";
import { unwrap } from "./http";
import { invalidateMoney, qk } from "./keys";
import { ApiError } from "./problem";
import type { components } from "./schema";

type S = components["schemas"];

/** Every field present; the counts and dates stay null until the import has run. */
type Nullable = "totalRows" | "postedRows" | "skippedRows" | "failureReason" | "startedAt" | "completedAt";
export type StatementImport = Omit<Required<S["ImportView"]>, "status" | "errors" | Nullable> & {
  status: ImportStatus;
  totalRows: number | null;
  postedRows: number | null;
  skippedRows: number | null;
  failureReason: string | null;
  startedAt: string | null;
  completedAt: string | null;
  errors: Required<S["RowError"]>[];
};
export type ImportStatus = "AWAITING_UPLOAD" | "PROCESSING" | "COMPLETED" | "FAILED";
export type UploadTarget = Required<S["UploadTarget"]>;
export type CreatedImport = { statementImport: StatementImport; upload: UploadTarget };

export const isFinished = (status: ImportStatus) => status === "COMPLETED" || status === "FAILED";

/** How often an import that is still running is asked for its status. */
export const POLL_MS = 1500;

/** The last imports of the user, newest first. */
export function useImportHistory() {
  return useQuery({
    queryKey: [...qk.imports, "history"] as const,
    queryFn: ({ signal }) => unwrap<StatementImport[]>(api.GET("/api/v1/imports", { signal })),
  });
}

/** One import, asked again every {@link POLL_MS} until it is finished. */
export function useImport(id: string | undefined) {
  return useQuery({
    queryKey: [...qk.imports, id] as const,
    enabled: id !== undefined,
    queryFn: ({ signal }) =>
      unwrap<StatementImport>(api.GET("/api/v1/imports/{importId}", { params: { path: { importId: id! } }, signal })),
    refetchInterval: (query) => (query.state.data && isFinished(query.state.data.status) ? false : POLL_MS),
  });
}

/**
 * Sends a file: creates the import, then PUTs the file to the target the API returned. On AWS that is a presigned S3
 * URL, which must be called without the API token (S3 rejects a request with two kinds of authentication); in local
 * development it is an API path on the same origin, which needs the token.
 */
export async function uploadStatement(file: File): Promise<CreatedImport> {
  const created = await unwrap<CreatedImport>(api.POST("/api/v1/imports", { body: { fileName: file.name } }));
  const { upload } = created;
  const headers = new Headers(upload.headers);
  if (upload.sameOrigin) {
    const token = await getAccessToken();
    if (token) headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(new Request(upload.url, { method: upload.method, headers, body: file }));
  if (!response.ok) {
    throw new ApiError(response.status, { title: "Không tải được tệp lên", detail: `Máy chủ lưu tệp trả về ${response.status}.` });
  }
  return created;
}

export function useUploadStatement() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: uploadStatement,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.imports }),
  });
}

/** Called once when an import finishes: its postings change balances, the inbox and the dashboard. */
export function useImportFinished() {
  const queryClient = useQueryClient();
  return () => Promise.all([invalidateMoney(queryClient), queryClient.invalidateQueries({ queryKey: qk.imports })]);
}
