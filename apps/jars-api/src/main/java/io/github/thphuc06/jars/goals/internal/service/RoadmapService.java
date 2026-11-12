package io.github.thphuc06.jars.goals.internal.service;

import io.github.thphuc06.jars.goals.internal.domain.FeasibilityCalculator;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.PlannerStateFactory;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapCompiler;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapCompiler.RoadmapInput;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse;
import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.PlannerState;
import io.github.thphuc06.jars.goals.internal.service.FeasibilityService.Assessment;
import io.github.thphuc06.jars.goals.internal.service.GoalAnalysis.Snapshot;
import io.github.thphuc06.jars.shared.UserId;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The staged plan of a goal ({@code GET /goals/{id}/roadmap}): gathers the facts about the user's money, labels them
 * (the "planner state") and hands them to the pure {@link RoadmapCompiler}.
 */
@Service
public class RoadmapService {

    private final GoalAnalysis analysis;
    private final FeasibilityService feasibility;

    RoadmapService(GoalAnalysis analysis, FeasibilityService feasibility) {
        this.analysis = analysis;
        this.feasibility = feasibility;
    }

    public RoadmapResponse roadmap(UserId userId, UUID goalId) {
        Snapshot s = analysis.snapshot(userId, goalId);
        Assessment assessment = feasibility.assess(s);

        PlannerState planner = PlannerStateFactory.build(
                s.cashflow(),
                s.totalBalance().amount(),
                s.currentAmount().amount(),
                s.target().amount(),
                s.openAnomalies(),
                assessment.hint());
        int timeline = Math.max(
                1, FeasibilityCalculator.monthsUntil(s.today(), s.goal().getTargetDate()));
        boolean reached = assessment.result().verdict() == FeasibilityCalculator.Verdict.ACHIEVED;
        return RoadmapCompiler.compile(new RoadmapInput(
                s.goal().getName(),
                s.target().amount(),
                timeline,
                s.currentAmount().amount(),
                planner,
                reached));
    }
}
