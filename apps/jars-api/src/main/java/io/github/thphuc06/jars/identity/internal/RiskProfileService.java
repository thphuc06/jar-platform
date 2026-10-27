package io.github.thphuc06.jars.identity.internal;

import io.github.thphuc06.jars.identity.RiskProfileAssessed;
import io.github.thphuc06.jars.shared.NotFoundException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PUT/GET /me/risk-profile (business-rules BR-4). */
@Service
public class RiskProfileService {

    private final RiskAssessmentRepository assessments;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    RiskProfileService(RiskAssessmentRepository assessments, ApplicationEventPublisher events, Clock clock) {
        this.assessments = assessments;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Scores the answers and stores them as a NEW assessment; the history is kept and the newest one is the profile.
     * Answering the same twice yields the same profile, so the PUT is idempotent in its effect on the resource.
     */
    @Transactional
    public RiskProfileView assess(UUID userId, RiskQuestionnaire.Answers answers) {
        RiskQuestionnaire.Result result = RiskQuestionnaire.assess(answers);
        RiskAssessment assessment = assessments.save(
                RiskAssessment.record(userId, answers, result, clock.instant().truncatedTo(ChronoUnit.MICROS)));
        events.publishEvent(new RiskProfileAssessed(
                userId, assessment.riskLevel(), assessment.score(), assessment.questionnaireVersion()));
        return assessment.toView();
    }

    @Transactional(readOnly = true)
    public RiskProfileView current(UUID userId) {
        return assessments
                .findFirstByUserIdOrderByAssessedAtDescIdDesc(userId)
                .map(RiskAssessment::toView)
                .orElseThrow(() -> new NotFoundException("risk-profile", "of the current user"));
    }
}
