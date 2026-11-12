/**
 * Goals: list, create, change, archive, feasibility, what-if scenario and roadmap.
 *
 * TODO(WP5-openapi): `/api/v1/goals/**` is NOT in contracts/openapi/jars-api.yaml yet (the goals module is built in
 * WP5). Everything marked "hand-written" below is written from docs/03-api/api-contract.md section 2 "Goals", the
 * `goals` table in docs/02-architecture/data-model.md and the hackathon UI's `src/types/roadmap.ts`. Money is
 * `{amount, currency}` and field names are camelCase, per api-contract section 1; the roadmap payload keeps the
 * snake_case names of `src/types/roadmap.ts`, because the doc says `GET /goals/{id}/roadmap` must match that type.
 *
 * When the spec contains the endpoints:
 *   1. `npm run gen:api`;
 *   2. delete `GoalsPaths` and its use in client.ts, and the hand-written interfaces below;
 *   3. derive `Goal`, `GoalFeasibility`, `RoadmapResponse`... from `components["schemas"]` with `Wire<>` in types.ts;
 *   4. check the open questions marked "confirm" (field names, whether roadmap amounts are numbers or Money).
 * The hooks keep their code: they already call the typed client with the final paths.
 */
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { Operation, PathItem } from "./handwritten";
import { unwrap, unwrapEmpty, wireBody } from "./http";
import { qk } from "./keys";
import type { Money } from "./types";

// ---- hand-written DTOs --------------------------------------------------------------------------------------

export type GoalStatus = "ACTIVE" | "ACHIEVED" | "ARCHIVED";

/** hand-written: `goals` table (name, target_amount, target_date, funding_jar_id, status) as camelCase JSON */
export interface Goal {
  id: string;
  name: string;
  targetAmount: Money;
  /** YYYY-MM-DD */
  targetDate: string;
  /** The jar whose balance counts as progress; null when the goal is not tied to a jar. */
  fundingJarId: string | null;
  status: GoalStatus;
}

export interface CreateGoalBody {
  name: string;
  /** whole VND as a decimal string */
  targetAmount: string;
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

/** hand-written, confirm: "Khả thi? Cần tiết kiệm bao nhiêu mỗi tháng?" (GET /goals/{id}/feasibility) */
export interface GoalFeasibility {
  feasible: boolean;
  requiredMonthlySaving: Money;
  monthsRemaining: number;
}

/** hand-written, confirm: what-if `{monthlySavingDelta, incomeDelta, ...}`; the answer is the feasibility under the change */
export interface GoalScenarioBody {
  monthlySavingDelta?: string;
  incomeDelta?: string;
}

// ---- hand-written roadmap contract (legacy src/types/roadmap.ts) ---------------------------------------------
// Amounts are plain numbers here, as in the hackathon type. confirm: the backend may send Money instead.

export type PhaseStatus = "current" | "upcoming" | "completed";

export interface RoadmapGoal {
  goal_type: string;
  target_amount: number;
  target_timeline_months: number;
  priority: string;
}

export interface PlannerState {
  cashflow_status: string;
  savings_capacity: { amount_monthly: number; label: string };
  runway_months: number;
  liquidity_pressure: string;
  anomaly_state: string;
  readiness_label: string;
  feasibility_hint: string;
  risk_band: string;
  goal_progress_ratio: number;
  buffer_status: string;
  source: string;
}

export interface PhaseData {
  phase_type: string;
  title: string;
  objective: string;
  entry_conditions: string[];
  exit_conditions: string[];
  expected_results: string[];
  service_ids: string[];
  status: PhaseStatus;
}

export interface MilestoneData {
  milestone_id: string;
  phase_type: string;
  title: string;
  unlock_rule: string;
  expected_result: string;
  status: PhaseStatus;
}

export interface ProjectionPoint {
  month: number;
  projected_amount: number;
}

export interface ProjectionChart {
  confidence_label: string;
  series: ProjectionPoint[];
  target_amount: number;
  projected_end_amount: number;
  gap_to_target: number;
  trend: string;
}

export interface NextActionCard {
  title: string;
  why: string;
  timeframe: string;
  expected_outcome: string;
  success_signal: string;
  follow_on_transition: string;
}

export interface StatusBanner {
  tone: string;
  title: string;
  message: string;
}

export interface KeyMetrics {
  current_monthly_capacity: { amount: number; label: string };
  current_progress_amount: number;
  current_progress_ratio: number;
  target_amount: number;
  timeline_months: number;
  projected_end_amount: number;
  feasibility_hint: string;
  readiness_label: string;
}

export interface TimelineNode {
  phase_type: string;
  title: string;
  status: PhaseStatus;
  objective: string;
}

export interface UnlockCondition {
  phase_type: string;
  status: string;
  conditions: string[];
}

export interface VisualizationSupport {
  timeline_nodes: TimelineNode[];
  phase_cards: PhaseData[];
  milestone_cards: MilestoneData[];
  projection_chart: ProjectionChart;
  next_action_card: NextActionCard;
  status_banner: StatusBanner;
  key_metrics: KeyMetrics;
  unlock_conditions: UnlockCondition[];
}

export interface ProjectionExplanation {
  basis: string;
  pace_assessment: string;
  target_gap_commentary: string;
  trend_commentary: string;
}

export interface RoadmapContract {
  schema_version: string;
  status: string;
  goal: RoadmapGoal;
  planner_state: PlannerState;
  journey_pattern: string;
  current_phase: string;
  phase_sequence: string[];
  phases: PhaseData[];
  milestones: MilestoneData[];
  projection: {
    confidence_label: string;
    basis: string;
    target_amount: number;
    projected_end_amount: number;
    target_timeline_months: number;
    series: ProjectionPoint[];
    caution: string;
  };
  next_best_action: NextActionCard;
  visualization_support: VisualizationSupport;
  /** confirm: present in the hackathon's saved-roadmap preview, not in the base type */
  maturity_events?: Array<{ event_type: string; title: string; condition: string }>;
}

export interface RoadmapResponse {
  schema_version: string;
  agent_id: string;
  status: string;
  result: {
    summary: string;
    roadmap_status: string;
    journey_pattern: string;
    current_phase: string;
    phase_sequence: string[];
  };
  roadmap_contract: RoadmapContract;
  ui_explanation: {
    summary: string;
    why_this_roadmap: string;
    why_current_phase: string;
    what_has_to_change_next: string;
  };
  explanation: {
    summary: string;
    projection_explanation: ProjectionExplanation;
    cautions: string[];
    confidence_note: string;
    /** confirm: present in the hackathon's saved-roadmap preview, not in the base type */
    next_action_service_explanation?: {
      selected_service: { service_id: string; display_name_vi: string; why_this_service_now?: string };
      explanation: string;
    };
  };
}

// ---- hand-written path description, in the shape of the generated `paths` ------------------------------------

type IdPath = { path: { id: string } };

export interface GoalsPaths {
  "/api/v1/goals": PathItem<{
    get: Operation<Goal[]>;
    post: Operation<Goal, { body: CreateGoalBody; status: 201 }>;
  }>;
  "/api/v1/goals/{id}": PathItem<{
    patch: Operation<Goal, IdPath & { body: UpdateGoalBody }>;
    delete: Operation<never, IdPath & { status: 204 }>;
  }>;
  "/api/v1/goals/{id}/feasibility": PathItem<{ get: Operation<GoalFeasibility, IdPath> }>;
  "/api/v1/goals/{id}/scenarios": PathItem<{ post: Operation<GoalFeasibility, IdPath & { body: GoalScenarioBody }> }>;
  "/api/v1/goals/{id}/roadmap": PathItem<{ get: Operation<RoadmapResponse, IdPath> }>;
}

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

/** What-if: how does the feasibility change with a different monthly saving or income. Nothing is saved. */
export function useGoalScenario() {
  return useMutation({
    mutationFn: ({ id, ...body }: GoalScenarioBody & { id: string }) =>
      unwrap<GoalFeasibility>(
        api.POST("/api/v1/goals/{id}/scenarios", { params: { path: { id } }, body: wireBody(body) }),
      ),
  });
}
