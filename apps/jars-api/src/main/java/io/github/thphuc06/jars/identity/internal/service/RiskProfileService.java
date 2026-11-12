package io.github.thphuc06.jars.identity.internal.service;

import io.github.thphuc06.jars.identity.internal.domain.RiskAssessment;
import io.github.thphuc06.jars.identity.internal.domain.RiskLevel;
import io.github.thphuc06.jars.identity.internal.domain.RiskQuestionnaire;
import io.github.thphuc06.jars.identity.internal.persistence.RiskAssessmentRepository;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Onboarding questionnaire: scores a submission and keeps the history. */
@Service
public class RiskProfileService {

    public record RiskProfile(int score, RiskLevel level, Instant assessedAt) {}

    private final RiskAssessmentRepository assessments;
    private final Clock clock;

    RiskProfileService(RiskAssessmentRepository assessments, Clock clock) {
        this.assessments = assessments;
        this.clock = clock;
    }

    @Transactional
    public RiskProfile submit(UserId userId, List<Integer> answers) {
        RiskQuestionnaire.Result result = RiskQuestionnaire.score(answers);
        RiskAssessment saved = assessments.save(new RiskAssessment(userId.value(), answers, result, clock.instant()));
        return toProfile(saved);
    }

    @Transactional(readOnly = true)
    public RiskProfile current(UserId userId) {
        return assessments
                .findFirstByUserIdOrderByAssessedAtDesc(userId.value())
                .map(RiskProfileService::toProfile)
                .orElseThrow(() -> DomainException.notFound("Hồ sơ rủi ro"));
    }

    private static RiskProfile toProfile(RiskAssessment a) {
        return new RiskProfile(a.getScore(), a.getRiskLevel(), a.getAssessedAt());
    }
}
