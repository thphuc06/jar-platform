package io.github.thphuc06.jars.insights.internal.model;

import io.github.thphuc06.jars.insights.internal.detection.DetectorState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Database form of {@link DetectorState}: one row per jar. The row is created by a native insert, never by JPA. */
@Entity
@Table(name = "anomaly_detector_state")
public class DetectorStateEntity {

    @Id
    @Column(name = "jar_id")
    private UUID jarId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(name = "mean", nullable = false)
    private double mean;

    @Column(name = "m2", nullable = false)
    private double m2;

    @Column(name = "cusum_pos", nullable = false)
    private double cusumPos;

    @Column(name = "cusum_neg", nullable = false)
    private double cusumNeg;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recent_amounts", nullable = false)
    private List<Double> recentAmounts;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DetectorStateEntity() {} // for JPA

    public DetectorState toState() {
        return new DetectorState(sampleCount, mean, m2, cusumPos, cusumNeg, recentAmounts);
    }

    public void apply(DetectorState state, Instant now) {
        this.sampleCount = state.count();
        this.mean = state.mean();
        this.m2 = state.m2();
        this.cusumPos = state.cusumPos();
        this.cusumNeg = state.cusumNeg();
        this.recentAmounts = state.recent();
        this.updatedAt = now;
    }

    public UUID getJarId() {
        return jarId;
    }

    public int getSampleCount() {
        return sampleCount;
    }
}
