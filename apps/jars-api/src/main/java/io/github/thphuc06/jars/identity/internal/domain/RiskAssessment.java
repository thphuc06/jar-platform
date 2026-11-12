package io.github.thphuc06.jars.identity.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** One submission of the onboarding questionnaire. Rows are never updated; the latest one is the current profile. */
@Entity
@Table(name = "risk_assessments")
public class RiskAssessment {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "answers", nullable = false, updatable = false)
    private List<Integer> answers;

    @Column(name = "score", nullable = false, updatable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, updatable = false)
    private RiskLevel riskLevel;

    @Column(name = "assessed_at", nullable = false, updatable = false)
    private Instant assessedAt;

    protected RiskAssessment() {} // for JPA

    public RiskAssessment(UUID userId, List<Integer> answers, RiskQuestionnaire.Result result, Instant assessedAt) {
        this.userId = userId;
        this.answers = List.copyOf(answers);
        this.score = result.score();
        this.riskLevel = result.level();
        this.assessedAt = assessedAt;
    }

    public int getScore() {
        return score;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public Instant getAssessedAt() {
        return assessedAt;
    }
}
