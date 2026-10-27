package io.github.thphuc06.jars.identity.internal.web;

import io.github.thphuc06.jars.identity.IdentityApi;
import io.github.thphuc06.jars.identity.RiskLevel;
import io.github.thphuc06.jars.identity.internal.RiskProfileService;
import io.github.thphuc06.jars.identity.internal.RiskProfileView;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Experience;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Horizon;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Liquidity;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.MainGoal;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.RiskTolerance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/risk-profile")
class RiskProfileController {

    private final IdentityApi identity;
    private final RiskProfileService riskProfiles;

    RiskProfileController(IdentityApi identity, RiskProfileService riskProfiles) {
        this.identity = identity;
        this.riskProfiles = riskProfiles;
    }

    /** The current profile; 404 {@code risk-profile-not-found} until the questionnaire has been answered once. */
    @GetMapping
    RiskProfileResponse current() {
        return RiskProfileResponse.from(
                riskProfiles.current(identity.currentUser().id()));
    }

    /** Answers the questionnaire (onboarding, or again later): all five answers are required. */
    @PutMapping
    RiskProfileResponse assess(@Valid @RequestBody RiskAnswersRequest request) {
        return RiskProfileResponse.from(
                riskProfiles.assess(identity.currentUser().id(), request.toAnswers()));
    }

    record RiskAnswersRequest(
            @NotNull RiskTolerance riskTolerance,
            @NotNull Horizon horizon,
            @NotNull Liquidity liquidity,
            @NotNull Experience experience,
            @NotNull MainGoal mainGoal) {

        RiskQuestionnaire.Answers toAnswers() {
            return new RiskQuestionnaire.Answers(riskTolerance, horizon, liquidity, experience, mainGoal);
        }
    }

    record RiskProfileResponse(
            RiskQuestionnaire.Answers answers,
            int score,
            int maxScore,
            RiskLevel riskLevel,
            boolean capped,
            int questionnaireVersion,
            Instant assessedAt) {

        static RiskProfileResponse from(RiskProfileView view) {
            return new RiskProfileResponse(
                    view.answers(),
                    view.score(),
                    RiskQuestionnaire.MAX_SCORE,
                    view.riskLevel(),
                    view.capped(),
                    view.questionnaireVersion(),
                    view.assessedAt());
        }
    }
}
