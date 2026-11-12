import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { unwrap, wireBody } from "./http";
import { invalidateMoney, qk } from "./keys";
import { nextCursor } from "./paging";
import type {
  PatchTransactionBody,
  Posted,
  PostTransactionBody,
  Transaction,
  TransactionPage,
  TransactionReceipt,
  TransactionType,
} from "./types";

export interface TransactionFilters {
  jarId?: string;
  categoryId?: string;
  type?: TransactionType;
  /** ISO date-time, inclusive */
  from?: string;
  /** ISO date-time */
  to?: string;
}

const PAGE_SIZE = 30;

/** Money Inbox: keyset pagination, newest first. Filters are applied by the server. */
export function useTransactionsInfinite(filters: TransactionFilters = {}) {
  return useInfiniteQuery({
    queryKey: [...qk.transactions, "list", filters] as const,
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      unwrap<TransactionPage>(
        api.GET("/api/v1/transactions", {
          params: { query: { ...filters, limit: PAGE_SIZE, cursor: pageParam } },
          signal,
        }),
      ),
    getNextPageParam: nextCursor,
  });
}

/** The few latest rows for the home screen. */
export function useRecentTransactions(limit = 5) {
  return useQuery({
    queryKey: [...qk.transactions, "recent", limit] as const,
    queryFn: async ({ signal }) =>
      (await unwrap<TransactionPage>(api.GET("/api/v1/transactions", { params: { query: { limit } }, signal })))
        .items,
  });
}

export interface PostTransactionVariables {
  body: PostTransactionBody;
  /** One key per intent, see `useIntentKey`: reuse it when the same intent is retried */
  idempotencyKey: string;
}

/** Record income or an expense (an expense needs a jar; income may have none). */
export function usePostTransaction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ body, idempotencyKey }: PostTransactionVariables): Promise<Posted> => {
      const result = await api.POST("/api/v1/transactions", {
        params: { header: { "Idempotency-Key": idempotencyKey } },
        body: wireBody(body),
      });
      return {
        receipt: result.data as unknown as TransactionReceipt,
        replayed: result.response.headers.get("Idempotency-Replayed") === "true",
      };
    },
    onSuccess: () => invalidateMoney(queryClient),
  });
}

/** Change the category and/or jar of an expense (a jar change books an ADJUST and a SPEND entry on the server). */
export function useRecategorizeTransaction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: PatchTransactionBody & { id: string }) =>
      unwrap<Transaction>(api.PATCH("/api/v1/transactions/{id}", { params: { path: { id } }, body })),
    onSuccess: () => invalidateMoney(queryClient),
  });
}
