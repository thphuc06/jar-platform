package io.github.thphuc06.jars.goals.internal.domain.roadmap;

import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.ACCUMULATE;
import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.MATURITY_TRANSITION;
import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.PROTECT_LIQUIDITY;
import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.READINESS_REVIEW;
import static io.github.thphuc06.jars.goals.internal.domain.roadmap.PhaseType.STABILIZE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.thphuc06.jars.goals.internal.domain.roadmap.RoadmapResponse.MilestoneData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MilestoneEngineTest {

    private static final List<PhaseType> FULL =
            List.of(PROTECT_LIQUIDITY, STABILIZE, ACCUMULATE, READINESS_REVIEW, MATURITY_TRANSITION);

    private static Map<String, String> statuses(List<MilestoneData> milestones) {
        Map<String, String> byPhase = new LinkedHashMap<>();
        milestones.forEach(m -> byPhase.put(m.phaseType(), m.status()));
        return byPhase;
    }

    @Test
    void milestonesFollowThePhaseProgress_likeTheLegacyTest() {
        // legacy test_milestones_follow_phase_progress_statuses: protect completed, stabilize current, accumulate upcoming
        Map<String, String> statuses = statuses(MilestoneEngine.build(FULL, STABILIZE));

        assertThat(statuses)
                .containsExactly(
                        Map.entry("protect_liquidity", "completed"),
                        Map.entry("stabilize", "current"),
                        Map.entry("accumulate", "upcoming"),
                        Map.entry("readiness_review", "upcoming"),
                        Map.entry("maturity_transition", "upcoming"));
    }

    @Test
    void thereIsOneMilestonePerPhase_numbered_withTheTextsOfItsPhase() {
        List<MilestoneData> milestones = MilestoneEngine.build(FULL, PROTECT_LIQUIDITY);

        assertThat(milestones).extracting(MilestoneData::milestoneId).containsExactly("ms_1", "ms_2", "ms_3", "ms_4", "ms_5");
        assertThat(milestones).extracting(MilestoneData::phaseType).containsExactlyElementsOf(
                FULL.stream().map(PhaseType::wire).toList());
        MilestoneData first = milestones.getFirst();
        assertThat(first.status()).isEqualTo("current");
        assertThat(first.title()).isEqualTo(PROTECT_LIQUIDITY.milestoneTitle());
        assertThat(first.unlockRule()).isEqualTo(PROTECT_LIQUIDITY.unlockRule());
        assertThat(first.expectedResult()).isEqualTo(PROTECT_LIQUIDITY.milestoneResult());
        assertThat(milestones.stream().filter(m -> m.status().equals("current"))).hasSize(1);
    }

    @Test
    void theLastPhaseAsCurrent_completesEverythingBefore() {
        assertThat(statuses(MilestoneEngine.build(FULL, MATURITY_TRANSITION)).values())
                .containsExactly("completed", "completed", "completed", "completed", "current");
    }

    @Test
    void aCurrentPhaseThatIsNotInTheSequence_isTreatedAsTheFirst() {
        List<MilestoneData> milestones = MilestoneEngine.build(List.of(ACCUMULATE, READINESS_REVIEW), PROTECT_LIQUIDITY);

        assertThat(milestones.getFirst().status()).isEqualTo("upcoming");
        assertThat(milestones.get(1).status()).isEqualTo("upcoming");
    }

    @Test
    void everyPhaseHasCompleteTexts() {
        for (PhaseType type : PhaseType.values()) {
            assertThat(type.title()).isNotBlank();
            assertThat(type.objective()).isNotBlank();
            assertThat(type.entryConditions()).isNotEmpty().doesNotContain("");
            assertThat(type.exitConditions()).isNotEmpty().doesNotContain("");
            assertThat(type.expectedResults()).isNotEmpty();
            assertThat(type.milestoneTitle()).isNotBlank();
            assertThat(type.unlockRule()).isNotBlank();
            assertThat(type.milestoneResult()).isNotBlank();
            assertThat(type.nextAction().title()).isNotBlank();
            assertThat(type.nextAction().why()).isNotBlank();
            assertThat(type.nextAction().timeframe()).isNotBlank();
            assertThat(type.nextAction().expectedOutcome()).isNotBlank();
            assertThat(type.nextAction().successSignal()).isNotBlank();
            assertThat(type.nextAction().followOnTransition()).isNotBlank();
            assertThat(PhaseType.fromWire(type.wire())).contains(type);
        }
        assertThat(PhaseType.fromWire("nope")).isEmpty();
        assertThat(MATURITY_TRANSITION.serviceIds()).isEmpty();
        assertThat(PROTECT_LIQUIDITY.serviceIds()).containsExactly("anomaly_review", "liquidity_guardrails", "emergency_buffer_setup");
    }

    @Test
    void theWireNamesAndPatternWordsAreTheLegacyOnes() {
        assertThat(PhaseType.values())
                .extracting(PhaseType::wire)
                .containsExactly("protect_liquidity", "stabilize", "accumulate", "readiness_review", "maturity_transition");
        assertThat(PhaseType.values())
                .extracting(PhaseType::shortName)
                .containsExactly("protect", "stabilize", "accumulate", "review", "maturity");
    }
}
