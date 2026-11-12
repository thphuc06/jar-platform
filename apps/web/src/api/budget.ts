/**
 * Budgeting: the plan of a month per jar, and splitting an income over the jars.
 *
 *   - GET  /budget/periods/{month}          planned against spent for every active jar
 *   - POST /budget/allocation-suggestions   how the server would divide an income (nothing is written)
 *   - POST /budget/allocations              books the income and the split in one transaction (Idempotency-Key)
 *
 * Response types come from the generated `schema.d.ts` through `DeepWire`. Note what the server expects when
 * applying: **amounts per jar**, adding up exactly to the income. Percentages are the screen's business, see
 * `splitByPercent` in lib/percent.ts.
 */
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { unwrap, wireBody } from "./http";
import { invalidateMoney, qk } from "./keys";
import type { components } from "./schema";
import type { DeepWire } from "./types";

type S = components["schemas"];

/** One active jar in a month. `closedAt` is null while the month is open. */
export type JarPeriod = Omit<DeepWire<S["JarPeriodView"]>, "closedAt"> & { closedAt: string | null };

export type BudgetPeriod = Omit<DeepWire<S["PeriodView"]>, "jars"> & { jars: JarPeriod[] };

export interface AllocationSuggestionBody {
  /** The income to split, whole VND as a decimal string */
  amount: string;
}

/** `sharePct` is the share the server proposes, `targetPct` the jar's own configured share; both in percent. */
export type AllocationSuggestion = DeepWire<S["Suggestion"]>;
export type AllocationLine = AllocationSuggestion["lines"][number];

export interface AllocationSplit {
  jarId: string;
  /** whole VND as a decimal string */
  amount: string;
}

/** What the user accepted. `splits` must add up to exactly `amount`; with `skip` only the decision is recorded. */
export interface ApplyAllocationBody {
  amount: string;
  splits?: AllocationSplit[];
  categoryId?: string;
  counterparty?: string;
  note?: string;
  occurredAt?: string;
  skip?: boolean;
}

export type AllocationResult = DeepWire<S["AllocationResult"]>;

/** Result of applying an allocation, with whether the server replayed an earlier attempt. */
export interface Allocated {
  result: AllocationResult;
  replayed: boolean;
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
    mutationFn: async ({ body, idempotencyKey }: ApplyAllocationVariables): Promise<Allocated> => {
      const response = await api.POST("/api/v1/budget/allocations", {
        params: { header: { "Idempotency-Key": idempotencyKey } },
        body: wireBody(body),
      });
      return {
        result: await unwrap<AllocationResult>(Promise.resolve(response)),
        replayed: response.response.headers.get("Idempotency-Replayed") === "true",
      };
    },
    onSuccess: () => invalidateMoney(queryClient),
  });
}
