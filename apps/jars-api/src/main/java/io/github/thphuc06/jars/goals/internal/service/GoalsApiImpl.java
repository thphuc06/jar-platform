package io.github.thphuc06.jars.goals.internal.service;

import io.github.thphuc06.jars.goals.GoalsApi;
import io.github.thphuc06.jars.shared.UserId;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
class GoalsApiImpl implements GoalsApi {

    private final GoalService goals;

    GoalsApiImpl(GoalService goals) {
        this.goals = goals;
    }

    @Override
    public List<GoalSummary> goals(UserId userId) {
        return goals.list(userId).stream()
                .map(g -> new GoalSummary(g.id(), g.name(), g.targetAmount(), g.targetDate(), g.status()))
                .toList();
    }
}
