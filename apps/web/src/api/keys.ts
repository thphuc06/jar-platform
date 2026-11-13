import type { QueryClient } from "@tanstack/react-query";

/** Query keys in one place, so invalidation after a mutation cannot drift from the hooks. */
export const qk = {
  me: ["me"] as const,
  riskProfile: ["me", "risk-profile"] as const,
  jars: ["jars"] as const,
  categories: ["categories"] as const,
  transactions: ["transactions"] as const,
  dashboard: ["dashboard"] as const,
  balances: ["balances"] as const,
  anomalies: ["anomalies"] as const,
  notifications: ["notifications"] as const,
  goals: ["goals"] as const,
  budget: ["budget"] as const,
  imports: ["imports"] as const,
  advisor: ["advisor"] as const,
};

/** Everything a posted transaction, a jar transfer or an income allocation can change. */
export function invalidateMoney(queryClient: QueryClient): Promise<unknown> {
  return Promise.all(
    [qk.jars, qk.transactions, qk.dashboard, qk.balances, qk.budget, qk.goals].map((queryKey) =>
      queryClient.invalidateQueries({ queryKey }),
    ),
  );
}
