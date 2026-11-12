package io.github.thphuc06.jars.insights.internal.model;

import io.github.thphuc06.jars.insights.internal.detection.SpendingDetector.Detector;
import io.github.thphuc06.jars.insights.internal.detection.SpendingDetector.Direction;
import io.github.thphuc06.jars.insights.internal.detection.SpendingDetector.Finding;
import io.github.thphuc06.jars.insights.internal.detection.SpendingDetector.Severity;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.ProblemTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** An unusual expense or spending trend that was detected, with the user's verdict once they give one. */
@Entity
@Table(name = "anomalies")
public class Anomaly {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "jar_id", nullable = false, updatable = false)
    private UUID jarId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "detector", nullable = false, updatable = false)
    private Detector detector;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, updatable = false)
    private Severity severity;

    @Column(name = "score", nullable = false, updatable = false)
    private double score;

    @Column(name = "threshold", nullable = false, updatable = false)
    private double threshold;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AnomalyStatus status;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @Column(name = "feedback_at")
    private Instant feedbackAt;

    protected Anomaly() {} // for JPA

    public Anomaly(UUID userId, UUID jarId, UUID transactionId, Finding finding, Money amount, Instant detectedAt) {
        this.userId = userId;
        this.jarId = jarId;
        this.transactionId = transactionId;
        this.detector = finding.detector();
        this.direction = finding.direction();
        this.severity = finding.severity();
        this.score = finding.score();
        this.threshold = finding.threshold();
        this.amount = amount.amount();
        this.status = AnomalyStatus.OPEN;
        this.detectedAt = detectedAt;
    }

    /** Records the user's verdict. {@code OPEN} is not a verdict. */
    public void giveFeedback(AnomalyStatus label, Instant now) {
        if (!label.isFeedback()) {
            throw new DomainException(
                    ProblemTypes.VALIDATION_FAILED, "Nhãn phải là CONFIRMED, EXPECTED hoặc FALSE_POSITIVE.");
        }
        this.status = label;
        this.feedbackAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getJarId() {
        return jarId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public Detector getDetector() {
        return detector;
    }

    public Direction getDirection() {
        return direction;
    }

    public Severity getSeverity() {
        return severity;
    }

    public double getScore() {
        return score;
    }

    public double getThreshold() {
        return threshold;
    }

    public Money amount() {
        return new Money(amount, Money.VND);
    }

    public AnomalyStatus getStatus() {
        return status;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }
}
