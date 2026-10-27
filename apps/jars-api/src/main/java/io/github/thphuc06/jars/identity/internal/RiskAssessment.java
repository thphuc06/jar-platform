package io.github.thphuc06.jars.identity.internal;

import io.github.thphuc06.jars.identity.RiskLevel;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Answers;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Experience;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Horizon;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Liquidity;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.MainGoal;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.Result;
import io.github.thphuc06.jars.identity.internal.RiskQuestionnaire.RiskTolerance;
import io.github.thphuc06.jars.shared.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/** One submission of the questionnaire (Flyway V7). Never changed: answering again inserts a new row. */
@Entity
@Immutable
@Table(name = "risk_assessments")
class RiskAssessment implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "questionnaire_version", nullable = false)
    private int questionnaireVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_tolerance", nullable = false)
    private RiskTolerance riskTolerance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Horizon horizon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Liquidity liquidity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Experience experience;

    @Enumerated(EnumType.STRING)
    @Column(name = "main_goal", nullable = false)
    private MainGoal mainGoal;

    @Column(nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false)
    private RiskLevel riskLevel;

    @Column(nullable = false)
    private boolean capped;

    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt;

    /** Assigned id and no {@code @Version}: tell Spring Data explicitly that a new row must be persisted. */
    @Transient
    private boolean persisted;

    protected RiskAssessment() {} // for JPA

    private RiskAssessment(UUID userId, Answers answers, Result result, Instant assessedAt) {
        this.id = Ids.newId();
        this.userId = userId;
        this.questionnaireVersion = RiskQuestionnaire.VERSION;
        this.riskTolerance = answers.riskTolerance();
        this.horizon = answers.horizon();
        this.liquidity = answers.liquidity();
        this.experience = answers.experience();
        this.mainGoal = answers.mainGoal();
        this.score = result.score();
        this.riskLevel = result.level();
        this.capped = result.capped();
        this.assessedAt = assessedAt;
    }

    static RiskAssessment record(UUID userId, Answers answers, Result result, Instant assessedAt) {
        return new RiskAssessment(userId, answers, result, assessedAt);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        persisted = true;
    }

    RiskProfileView toView() {
        return new RiskProfileView(
                new Answers(riskTolerance, horizon, liquidity, experience, mainGoal),
                score,
                riskLevel,
                capped,
                questionnaireVersion,
                assessedAt);
    }

    UUID userId() {
        return userId;
    }

    int score() {
        return score;
    }

    RiskLevel riskLevel() {
        return riskLevel;
    }

    int questionnaireVersion() {
        return questionnaireVersion;
    }
}
