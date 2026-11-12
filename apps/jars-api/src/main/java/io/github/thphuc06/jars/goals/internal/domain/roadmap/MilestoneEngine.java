package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.MilestoneData;
import java.util.ArrayList;
import java.util.List;

/** One milestone per stage, with the status of its stage. Port of {@code milestone_engine.py}. */
public final class MilestoneEngine {

    private MilestoneEngine() {}

    /** Milestones {@code ms_1}, {@code ms_2}, ... in the order of the stages; a stage before the current one is done. */
    public static List<MilestoneData> build(List<PhaseType> sequence, PhaseType current) {
        List<MilestoneData> milestones = new ArrayList<>();
        int currentIndex = Math.max(0, sequence.indexOf(current));
        for (int i = 0; i < sequence.size(); i++) {
            PhaseType phase = sequence.get(i);
            String status = i < currentIndex ? "completed" : phase == current ? "current" : "upcoming";
            milestones.add(new MilestoneData(
                    "ms_" + (i + 1),
                    phase.wire(),
                    phase.milestoneTitle(),
                    phase.unlockRule(),
                    phase.milestoneResult(),
                    status));
        }
        return List.copyOf(milestones);
    }
}
