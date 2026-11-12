/**
 * Budgeting: the plan of a month per jar, and splitting an income over the jars.
 *
 * TODO(WP5-openapi): `/api/v1/budget/**` is NOT in contracts/openapi/jars-api.yaml yet (the budgeting module is built
 * in WP5). The interfaces below are hand-written from docs/03-api/api-contract.md section 2 "Budgeting":
 *   - GET  /budget/periods/{month}          "Kế hoạch và thực chi theo hũ trong tháng"
 *   - POST /budget/allocation-suggestions   "Gợi ý tỷ lệ phân bổ cho khoản thu nhập {amount}"
 *   - POST /budget/allocations              "Áp dụng phân bổ thu nhập vào các hũ" (Idempotency-Key); the shares of the
 *                                           jars must add up to exactly 100 (api-contract, PATCH /jars/{id})
 * The doc fixes the paths and the request `{amount}` of the suggestion; every other field name is a proposal marked
 * "confirm". Money is `{amount, currency}` (api-contract section 1).
 *
 * When the spec contains the endpoints:
 *   1. `npm run gen:api`;
 *   2. delete `BudgetPaths` and its use in client.ts, and the hand-written interfaces below;
 *   3. derive `BudgetPeriod`, `AllocationSuggestion`, ... from `components["schemas"]` with `Wire<>` in types.ts;
 *   4. fix the "confirm" fields. The hooks keep their code.
 */
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { Operation, PathItem } from "./handwritten";
import { unwrap, wireBody } from "./http";
import { invalidateMoney, qk } from "./keys";
import type { Money, Posted, TransactionReceipt } from "./types";

// ---- hand-written DTOs --------------------------------------------------------------------------------------

/** confirm: one jar in a month's plan */
export interface BudgetJarLine {
  jarId: string;
  code: string;
  name: string;
  /** The jar's share of income, in percent */
  targetPct: number;
  planned: Money;
  spent: Money;
}

/** confirm */
export interface BudgetPeriod {
  /** YYYY-MM */
  month: string;
  income: Money;
  jars: BudgetJarLine[];
}

export interface AllocationSuggestionBody {
  /** The income to split, whole VND as a decimal string */
  amount: string;
}

/** confirm: a suggested share of the income for one jar */
export interface AllocationLine {
  jarId: string;
  /** Percent, 0..100 */
  pct: number;
  amount: Money;
}

/** confirm: the suggestion for the income; the lines add up to 100 % */
export interface AllocationSuggestion {
  amount: Money;
  lines: AllocationLine[];
}

/** confirm: what the user accepted, possibly after editing the percentages */
export interface ApplyAllocationBody {
  amount: string;
  lines: Array<{ jarId: string; pct: number }>;
}

// ---- hand-written path description, in the shape of the generated `paths` ------------------------------------

export interface BudgetPaths {
  "/api/v1/budget/periods/{month}": PathItem<{ get: Operation<BudgetPeriod, { path: { month: string } }> }>;
  "/api/v1/budget/allocation-suggestions": PathItem<{
    post: Operation<AllocationSuggestion, { body: AllocationSuggestionBody }>;
  }>;
  "/api/v1/budget/allocations": PathItem<{
    post: Operation<TransactionReceipt, { body: ApplyAllocationBody; header: { "Idempotency-Key": string } }>;
  }>;
}

// ---- hooks ---------------------------------------------------------------------------------------------------

/** Plan and actual spending of every jar in a month (`YYYY-MM`). */
export function useBudgetPeriod(month: string) {
  return useQuery({
    queryKey: [...qk.budget, "period", month] as const,
    queryFn: ({ signal }) =>
      unwrap<BudgetPeriod>(api.GET("/api/v1/budget/periods/{month}", { params: { path: { month } }, signal })),
    retry: false,
  });
}

/** Asks the server how to split an income over the jars. Nothing is booked. */
export function useAllocationSuggestion() {
  return useMutation({
    mutationFn: (body: AllocationSuggestionBody) =>
      unwrap<AllocationSuggestion>(api.POST("/api/v1/budget/allocation-suggestions", { body: wireBody(body) })),
  });
}

export interface ApplyAllocationVariables {
  body: ApplyAllocationBody;
  /** One key per intent, see `useIntentKey`: reuse it when the same intent is retried */
  idempotencyKey: string;
}

/** Books the income and splits it over the jars (money changes: needs an Idempotency-Key). */
export function useApplyAllocation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ body, idempotencyKey }: ApplyAllocationVariables): Promise<Posted> => {
      const result = await api.POST("/api/v1/budget/allocations", {
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
