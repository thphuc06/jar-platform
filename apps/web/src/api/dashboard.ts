import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import { unwrap } from "./http";
import { qk } from "./keys";
import type { DailyBalance, Dashboard } from "./types";

/** Everything the home screen needs in one call: balances, month flow, spend by category and jar, 6-month trend. */
export function useDashboard(month?: string) {
  return useQuery({
    queryKey: [...qk.dashboard, month ?? "current"] as const,
    queryFn: ({ signal }) => unwrap<Dashboard>(api.GET("/api/v1/dashboard", { params: { query: { month } }, signal })),
  });
}

/** Total balance per day, oldest first, for the balance-vs-goal chart. */
export function useDailyBalances(days = 30) {
  return useQuery({
    queryKey: [...qk.balances, "daily", days] as const,
    queryFn: ({ signal }) =>
      unwrap<DailyBalance[]>(api.GET("/api/v1/balances/daily", { params: { query: { days } }, signal })),
  });
}
