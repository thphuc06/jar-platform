import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { unwrap } from "./http";
import { qk } from "./keys";
import { nextCursor } from "./paging";
import type { Anomaly, AnomalyFeedback, AnomalyPage, AnomalyStatus } from "./types";

const PAGE_SIZE = 20;

/** Alerts screen: anomalies detected on the user's jars, newest first, optionally one status only. */
export function useAnomalies(status?: AnomalyStatus) {
  return useInfiniteQuery({
    queryKey: [...qk.anomalies, "list", status ?? "all"] as const,
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      unwrap<AnomalyPage>(
        api.GET("/api/v1/anomalies", { params: { query: { status, limit: PAGE_SIZE, cursor: pageParam } }, signal }),
      ),
    getNextPageParam: nextCursor,
  });
}

/** The user's verdict on an anomaly. Labels other than the three below (including OPEN) are rejected with 422. */
export function useAnomalyFeedback() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, label }: { id: string; label: AnomalyFeedback }) =>
      unwrap<Anomaly>(api.POST("/api/v1/anomalies/{id}/feedback", { params: { path: { id } }, body: { label } })),
    onSuccess: async () => {
      // the anomaly moves to another status list, and the dashboard counts the open ones
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: qk.anomalies }),
        queryClient.invalidateQueries({ queryKey: qk.dashboard }),
      ]);
    },
  });
}
