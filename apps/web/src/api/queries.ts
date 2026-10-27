import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, unwrap, type components } from "@/api";

export type RiskAnswers = components["schemas"]["RiskAnswersRequest"];
export type Amendment = components["schemas"]["AmendTransactionRequest"];

export interface TransactionFilter {
  jarId?: string;
  categoryId?: string;
  type?: "INCOME" | "EXPENSE" | "JAR_TRANSFER";
}

/**
 * Hierarchical keys: invalidating ["transactions"] refreshes every list and the recent-transactions card at once.
 * Money moved by a write changes jars, the dashboard and the balance curve too, so those share one invalidation.
 */
export const queryKeys = {
  me: ["me"] as const,
  riskProfile: ["me", "risk-profile"] as const,
  dashboard: (month?: string) => ["dashboard", month ?? "current"] as const,
  dailyBalances: (days: number) => ["balances", "daily", days] as const,
  jars: ["jars"] as const,
  categories: ["categories"] as const,
  transactions: (filter: TransactionFilter) => ["transactions", "list", filter] as const,
  recentTransactions: (limit: number) => ["transactions", "recent", limit] as const,
};

const MONEY_VIEWS = [["transactions"], ["dashboard"], ["balances"], ["jars"]] as const;

/** The signed-in user; the first call also creates the internal user (flow F1). */
export function useMe() {
  return useQuery({ queryKey: queryKeys.me, queryFn: () => unwrap(api.GET("/api/v1/me")) });
}

/** The current risk profile, or null before the questionnaire was answered (404 risk-profile-not-found). */
export function useRiskProfile(enabled = true) {
  return useQuery({
    queryKey: queryKeys.riskProfile,
    enabled,
    queryFn: async () => {
      try {
        return await unwrap(api.GET("/api/v1/me/risk-profile"));
      } catch (e) {
        if (e instanceof ApiError && e.code === "risk-profile-not-found") {
          return null;
        }
        throw e;
      }
    },
  });
}

export function useAssessRiskProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (answers: RiskAnswers) => unwrap(api.PUT("/api/v1/me/risk-profile", { body: answers })),
    onSuccess: (profile) => queryClient.setQueryData(queryKeys.riskProfile, profile),
  });
}

/** Everything the dashboard shows, in one request. No month = the current month in Vietnam time (server side). */
export function useDashboard(month?: string) {
  return useQuery({
    queryKey: queryKeys.dashboard(month),
    queryFn: () => unwrap(api.GET("/api/v1/dashboard", { params: { query: month ? { month } : {} } })),
    // keep the previous month on screen while the next one loads, instead of flashing a skeleton
    placeholderData: keepPreviousData,
  });
}

export function useDailyBalances(days: number) {
  return useQuery({
    queryKey: queryKeys.dailyBalances(days),
    queryFn: () => unwrap(api.GET("/api/v1/balances/daily", { params: { query: { days } } })),
  });
}

export function useJars() {
  return useQuery({ queryKey: queryKeys.jars, queryFn: () => unwrap(api.GET("/api/v1/jars")) });
}

/** Categories rarely change: cache them for the whole session. */
export function useCategories() {
  return useQuery({
    queryKey: queryKeys.categories,
    queryFn: () => unwrap(api.GET("/api/v1/categories")),
    staleTime: Infinity,
  });
}

export function useRecentTransactions(limit: number) {
  return useQuery({
    queryKey: queryKeys.recentTransactions(limit),
    queryFn: () => unwrap(api.GET("/api/v1/transactions", { params: { query: { limit } } })),
  });
}

export const PAGE_SIZE = 30;

/**
 * The Money Inbox: keyset pages (P1.7). Each page's nextCursor is the pageParam of the next one; null = last page.
 * The cursor is opaque: the UI never builds or edits it.
 */
export function useTransactionPages(filter: TransactionFilter) {
  return useInfiniteQuery({
    queryKey: queryKeys.transactions(filter),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET("/api/v1/transactions", {
          params: { query: { limit: PAGE_SIZE, cursor: pageParam, ...filter } },
        }),
      ),
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
  });
}

/**
 * PATCH /transactions/{id} is a merge patch: send ONLY the changed fields; an explicit null clears categoryId or note.
 * Amount and type never change (BR-2). Moving jars changes balances, so every money view is refreshed afterwards.
 */
export function useAmendTransaction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, changes }: { id: string; changes: Amendment }) =>
      unwrap(api.PATCH("/api/v1/transactions/{id}", { params: { path: { id } }, body: changes })),
    onSuccess: () =>
      Promise.all(MONEY_VIEWS.map((queryKey) => queryClient.invalidateQueries({ queryKey: [...queryKey] }))),
  });
}
