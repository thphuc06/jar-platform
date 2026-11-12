/**
 * Goals: list, create, change, archive, feasibility, what-if scenarios and the roadmap.
 *
 * Response types come from the generated `schema.d.ts` (contracts/openapi/jars-api.yaml) through `DeepWire`, so a
 * field the backend renames fails `npm run typecheck` here instead of showing up as an empty screen. JSON names are
 * camelCase everywhere, including the roadmap (the hackathon prototype used snake_case; the backend does not).
 */
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { unwrap, unwrapEmpty, wireBody } from "./http";
import { qk } from "./keys";
import type { DeepWire } from "./types";
import type { components } from "./schema";

type S = components["schemas"];

// ---- goals --------------------------------------------------------------------------------------------------

export type GoalStatus = "ACTIVE" | "ACHIEVED" | "ARCHIVED";

/** `fundingJarId` is null when the goal is not tied to a jar. */
export type Goal = Omit<DeepWire<S["GoalView"]>, "status" | "fundingJarId"> & {
  status: GoalStatus;
  fundingJarId: string | null;
};

export interface CreateGoalBody {
  name: string;
  /** whole VND as a decimal string */
  targetAmount: string;
  /** YYYY-MM-DD */
  targetDate: string;
  fundingJarId?: string;
}

export interface UpdateGoalBody {
  name?: string;
  targetAmount?: string;
  targetDate?: string;
  fundingJarId?: string;
  status?: GoalStatus;
}

/** "Is it feasible, and how much must be saved per month?" (GET /goals/{id}/feasibility) */
export type GoalFeasibility = DeepWire<S["FeasibilityView"]>;

/** What-if (POST /goals/{id}/scenarios): every field is optional; amounts are decimal strings of whole VND. */
export interface GoalScenarioBody {
  monthlySavingDelta?: string;
  incomeDelta?: string;
  spendingDelta?: string;
  oneOffContribution?: string;
  horizonMonths?: number;
  /** Same seed, same answer: the Monte Carlo run is reproducible. */
  seed?: number;
}

export type GoalScenarios = DeepWire<S["ScenarioView"]>;
export type ScenarioResult = DeepWire<S["ScenarioResult"]>;
export type ProjectionSummary = DeepWire<S["ProjectionSummary"]>;

// ---- roadmap ------------------------------------------------------------------------------------------------
// Amounts here are plain numbers (not Money), as the backend sends them.

export type RoadmapResponse = DeepWire<S["RoadmapResponse"]>;
export type RoadmapContract = RoadmapResponse["roadmapContract"];
export type RoadmapGoal = DeepWire<S["RoadmapGoal"]>;
export type PlannerState = DeepWire<S["PlannerState"]>;
/** `status` is "current", "upcoming" or "completed". */
export type PhaseData = DeepWire<S["PhaseData"]>;
export type MilestoneData = DeepWire<S["MilestoneData"]>;
export type ProjectionPoint = DeepWire<S["ProjectionPoint"]>;
export type ProjectionChart = DeepWire<S["ProjectionChart"]>;
export type NextActionCard = DeepWire<S["NextActionCard"]>;
export type StatusBanner = DeepWire<S["StatusBanner"]>;
export type KeyMetrics = DeepWire<S["KeyMetrics"]>;
export type TimelineNode = DeepWire<S["TimelineNode"]>;
export type UnlockCondition = DeepWire<S["UnlockCondition"]>;
export type VisualizationSupport = DeepWire<S["VisualizationSupport"]>;
export type ProjectionExplanation = DeepWire<S["ProjectionExplanation"]>;

// ---- hooks ---------------------------------------------------------------------------------------------------

export function useGoals() {
  return useQuery({
    queryKey: qk.goals,
    queryFn: ({ signal }) => unwrap<Goal[]>(api.GET("/api/v1/goals", { signal })),
  });
}

export function useCreateGoal() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateGoalBody) => unwrap<Goal>(api.POST("/api/v1/goals", { body: wireBody(body) })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.goals }),
  });
}

export function useUpdateGoal() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: UpdateGoalBody & { id: string }) =>
      unwrap<Goal>(api.PATCH("/api/v1/goals/{id}", { params: { path: { id } }, body: wireBody(body) })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.goals }),
  });
}

/** Archive (the goal is hidden, not deleted). */
export function useArchiveGoal() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => unwrapEmpty(api.DELETE("/api/v1/goals/{id}", { params: { path: { id } } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: qk.goals }),
  });
}

export function useGoalFeasibility(goalId: string | undefined) {
  return useQuery({
    queryKey: [...qk.goals, goalId, "feasibility"] as const,
    queryFn: ({ signal }) =>
      unwrap<GoalFeasibility>(api.GET("/api/v1/goals/{id}/feasibility", { params: { path: { id: goalId! } }, signal })),
    enabled: goalId !== undefined,
  });
}

export function useGoalRoadmap(goalId: string | undefined) {
  return useQuery({
    queryKey: [...qk.goals, goalId, "roadmap"] as const,
    queryFn: ({ signal }) =>
      unwrap<RoadmapResponse>(api.GET("/api/v1/goals/{id}/roadmap", { params: { path: { id: goalId! } }, signal })),
    enabled: goalId !== undefined,
    retry: false,
  });
}

/** What-if: the same goal under a different monthly saving, income, spending or one-off sum. Nothing is saved. */
export function useGoalScenario() {
  return useMutation({
    mutationFn: ({ id, ...body }: GoalScenarioBody & { id: string }) =>
      unwrap<GoalScenarios>(
        api.POST("/api/v1/goals/{id}/scenarios", { params: { path: { id } }, body: wireBody(body) }),
      ),
  });
}
