import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, unwrap, type components } from "@/api";

export type RiskAnswers = components["schemas"]["RiskAnswersRequest"];

export const queryKeys = {
  me: ["me"] as const,
  riskProfile: ["me", "risk-profile"] as const,
};

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
