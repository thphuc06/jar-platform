package io.github.thphuc06.jars.insights.internal.service;

import io.github.thphuc06.jars.insights.AnomalyDetected;
import io.github.thphuc06.jars.insights.internal.detection.DetectorState;
import io.github.thphuc06.jars.insights.internal.detection.SpendingDetector;
import io.github.thphuc06.jars.insights.internal.detection.SpendingDetector.Finding;
import io.github.thphuc06.jars.insights.internal.model.Anomaly;
import io.github.thphuc06.jars.insights.internal.model.DetectorStateEntity;
import io.github.thphuc06.jars.insights.internal.persistence.AnomalyRepository;
import io.github.thphuc06.jars.insights.internal.persistence.DetectorStateRepository;
import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.platform.observability.AfterCommit;
import io.github.thphuc06.jars.platform.observability.BusinessMetrics;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.UserId;
import io.github.thphuc06.jars.shared.Uuids;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Feeds one expense to the jar's detector and records and announces what it finds. */
@Service
class DetectionService {

    private static final String UNKNOWN_JAR = "Hũ";

    private final DetectorStateRepository states;
    private final AnomalyRepository anomalies;
    private final LedgerApi ledger;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final MeterRegistry meters;
    private final SpendingDetector detector = new SpendingDetector();

    DetectionService(
            DetectorStateRepository states,
            AnomalyRepository anomalies,
            LedgerApi ledger,
            ApplicationEventPublisher events,
            Clock clock,
            MeterRegistry meters) {
        this.meters = meters;
        this.states = states;
        this.anomalies = anomalies;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
    }

    /** Creates the jar's state row if it does not exist yet; safe to call concurrently and repeatedly. */
    @Transactional(propagation = Propagation.MANDATORY)
    void ensureState(UserId userId, UUID jarId) {
        states.insertIfAbsent(jarId, userId.value());
    }

    /**
     * Must run in a transaction: the jar's state row is locked until it ends, so events of one jar are applied one at
     * a time and the CUSUM recursion sees every expense exactly once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Anomaly> observeExpense(UserId userId, UUID jarId, UUID transactionId, Money amount, Instant occurredAt) {
        ensureState(userId, jarId);
        DetectorStateEntity entity = states.lockByJarId(jarId).orElseThrow();
        DetectorState state = entity.toState();

        Optional<Finding> finding = detector.observe(state, amount.amount().doubleValue());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        entity.apply(state, now);

        return finding.map(f -> {
            Anomaly anomaly = anomalies.save(new Anomaly(userId.value(), jarId, transactionId, f, amount, now));
            events.publishEvent(new AnomalyDetected(
                    Uuids.v7(),
                    anomaly.getId(),
                    userId,
                    jarId,
                    jarName(userId, jarId),
                    f.detector().name(),
                    f.direction().name(),
                    f.severity().name(),
                    f.score(),
                    f.threshold(),
                    transactionId,
                    amount,
                    now));
            String detectorName = f.detector().name();
            String severity = f.severity().name();
            AfterCommit.run(() -> meters.counter(
                            BusinessMetrics.ANOMALIES_DETECTED, "detector", detectorName, "severity", severity)
                    .increment());
            return anomaly;
        });
    }

    private String jarName(UserId userId, UUID jarId) {
        return ledger.jars(userId).stream()
                .filter(j -> j.id().equals(jarId))
                .map(JarSummary::name)
                .findFirst()
                .orElse(UNKNOWN_JAR);
    }
}
