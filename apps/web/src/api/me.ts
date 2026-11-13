import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { unwrap, wireBody } from "./http";
import { qk } from "./keys";
import type { AgeBand, IncomeSituation } from "@/lib/profileFacts";
import { ApiError } from "./problem";
import type { Me, RiskProfile } from "./types";

/** First call after sign-in: the server creates the user and the six jars lazily (flows.md F1). */
export function useMe() {
  return useQuery({
    queryKey: qk.me,
    queryFn: ({ signal }) => unwrap<Me>(api.GET("/api/v1/me", { signal })),
    staleTime: 5 * 60_000,
  });
}

export function useUpdateMe() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { displayName?: string; locale?: "vi-VN" | "en-US"; ageBand?: AgeBand; incomeSituation?: IncomeSituation }) =>
      unwrap<Me>(api.PATCH("/api/v1/me", { body })),
    onSuccess: (me) => queryClient.setQueryData(qk.me, me),
  });
}

/** `null` while the user has not done the onboarding questionnaire (the server answers 404). */
export function useRiskProfile() {
  return useQuery({
    queryKey: qk.riskProfile,
    queryFn: async ({ signal }) => {
      try {
        return await unwrap<RiskProfile>(api.GET("/api/v1/me/risk-profile", { signal }));
      } catch (error) {
        if (error instanceof ApiError && error.isNotFound) return null;
        throw error;
      }
    },
  });
}

/** Five answers, each the index of the chosen option, in the order of the questions on the onboarding screen. */
export function useSubmitRiskProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (answers: number[]) =>
      unwrap<RiskProfile>(api.PUT("/api/v1/me/risk-profile", { body: wireBody({ answers }) })),
    onSuccess: (profile) => {
      queryClient.setQueryData(qk.riskProfile, profile);
      return queryClient.invalidateQueries({ queryKey: qk.me });
    },
  });
}
