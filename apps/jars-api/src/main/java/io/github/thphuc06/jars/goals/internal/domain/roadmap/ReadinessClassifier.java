package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PlannerState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether there is enough known about the goal and the user's money to draw a roadmap. A port of
 * {@code classify_missing_fields} (roadmap_context.py) and {@code classify_request} (layer1_sufficiency.py): a field
 * that is unknown or null is "missing", and the number of missing goal and planner fields picks the class.
 */
public final class ReadinessClassifier {

    public static final String READY = "ready";
    public static final String PARTIAL = "partial_but_usable";
    public static final String INSUFFICIENT_GOAL = "insufficient_goal_data";
    public static final String INSUFFICIENT_STATE = "insufficient_financial_state";

    private ReadinessClassifier() {}

    public record Classification(String status, List<String> missingFields) {
        /** The roadmap can only be drawn from a class where the goal and the plan are known well enough. */
        public boolean needsSetupRoadmap() {
            return INSUFFICIENT_GOAL.equals(status) || INSUFFICIENT_STATE.equals(status);
        }
    }

    public static Classification classify(BigDecimal targetAmount, int timelineMonths, PlannerState planner) {
        List<String> goalMissing = new ArrayList<>();
        if (targetAmount == null || targetAmount.signum() <= 0) {
            goalMissing.add("goal.target_amount");
        }
        if (timelineMonths <= 0) {
            goalMissing.add("goal.target_timeline_months");
        }
        List<String> plannerMissing = new ArrayList<>();
        addIfUnknown(plannerMissing, "cashflow_status", planner.cashflowStatus());
        addIfUnknown(plannerMissing, "liquidity_pressure", planner.liquidityPressure());
        addIfUnknown(plannerMissing, "anomaly_state", planner.anomalyState());
        addIfUnknown(plannerMissing, "readiness_label", planner.readinessLabel());
        addIfUnknown(plannerMissing, "feasibility_hint", planner.feasibilityHint());
        addIfUnknown(plannerMissing, "risk_band", planner.riskBand());
        if (planner.runwayMonths() == null) {
            plannerMissing.add("planner_state.runway_months");
        }
        if (planner.savingsCapacity() == null || planner.savingsCapacity().amountMonthly() == null) {
            plannerMissing.add("planner_state.savings_capacity.amount_monthly");
        }

        List<String> missing = new ArrayList<>(goalMissing);
        missing.addAll(plannerMissing);

        String status;
        if (missing.isEmpty()) {
            status = READY;
        } else if (goalMissing.size() <= 1 && plannerMissing.size() <= 2) {
            status = PARTIAL;
        } else if (!goalMissing.isEmpty()) {
            status = INSUFFICIENT_GOAL;
        } else {
            status = INSUFFICIENT_STATE;
        }
        return new Classification(status, List.copyOf(missing));
    }

    private static void addIfUnknown(List<String> missing, String field, String value) {
        if (value == null
                || value.isBlank()
                || PlannerStateFactory.UNKNOWN.equals(value.trim().toLowerCase())) {
            missing.add("planner_state." + field);
        }
    }
}
