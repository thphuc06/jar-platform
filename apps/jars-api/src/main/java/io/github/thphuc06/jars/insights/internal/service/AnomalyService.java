package io.github.thphuc06.jars.insights.internal.service;

import io.github.thphuc06.jars.insights.internal.model.Anomaly;
import io.github.thphuc06.jars.insights.internal.model.AnomalyStatus;
import io.github.thphuc06.jars.insights.internal.persistence.AnomalyRepository;
import io.github.thphuc06.jars.ledger.JarSummary;
import io.github.thphuc06.jars.ledger.LedgerApi;
import io.github.thphuc06.jars.shared.DomainException;
import io.github.thphuc06.jars.shared.KeysetCursor;
import io.github.thphuc06.jars.shared.Money;
import io.github.thphuc06.jars.shared.ProblemTypes;
import io.github.thphuc06.jars.shared.UserId;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The alerts screen: list the user's anomalies and record their verdict on each. */
@Service
public class AnomalyService {

    public record AnomalyView(
            UUID id,
            UUID jarId,
            String jarName,
            UUID transactionId,
            String detector,
            String direction,
            String severity,
            double score,
            double threshold,
            Money amount,
            String status,
            Instant detectedAt) {}

    public record AnomalyPage(List<AnomalyView> items, String nextCursor) {}

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final AnomalyRepository anomalies;
    private final LedgerApi ledger;
    private final Clock clock;

    AnomalyService(AnomalyRepository anomalies, LedgerApi ledger, Clock clock) {
        this.anomalies = anomalies;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AnomalyPage list(UserId userId, AnomalyStatus status, Integer limit, String cursor) {
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new DomainException(ProblemTypes.BAD_REQUEST, "limit phải từ 1 đến " + MAX_PAGE_SIZE + ".");
        }
        var statuses = status == null ? EnumSet.allOf(AnomalyStatus.class) : EnumSet.of(status);
        Limit fetch = Limit.of(pageSize + 1); // one extra row tells whether another page exists

        List<Anomaly> rows;
        if (cursor == null || cursor.isBlank()) {
            rows = anomalies.firstPage(userId.value(), statuses, fetch);
        } else {
            KeysetCursor position = KeysetCursor.decode(cursor);
            rows = anomalies.pageAfter(userId.value(), statuses, position.at(), position.id(), fetch);
        }
        boolean hasMore = rows.size() > pageSize;
        List<Anomaly> page = hasMore ? rows.subList(0, pageSize) : rows;

        Map<UUID, String> jarNames =
                ledger.jars(userId).stream().collect(Collectors.toMap(JarSummary::id, JarSummary::name));
        List<AnomalyView> items = page.stream().map(a -> view(a, jarNames)).toList();
        String next = hasMore
                ? new KeysetCursor(
                                page.getLast().getDetectedAt(), page.getLast().getId())
                        .encode()
                : null;
        return new AnomalyPage(items, next);
    }

    /** Records the user's verdict on one of their own alerts (404 for anyone else's). */
    @Transactional
    public AnomalyView feedback(UserId userId, UUID anomalyId, String label) {
        AnomalyStatus verdict = parse(label);
        Anomaly anomaly = anomalies
                .findByIdAndUserId(anomalyId, userId.value())
                .orElseThrow(() -> DomainException.notFound("Cảnh báo"));
        anomaly.giveFeedback(verdict, clock.instant());
        Map<UUID, String> jarNames =
                ledger.jars(userId).stream().collect(Collectors.toMap(JarSummary::id, JarSummary::name));
        return view(anomaly, jarNames);
    }

    @Transactional(readOnly = true)
    public long openCount(UserId userId) {
        return anomalies.countByUserIdAndStatus(userId.value(), AnomalyStatus.OPEN);
    }

    private static AnomalyStatus parse(String label) {
        try {
            AnomalyStatus status = AnomalyStatus.valueOf(label);
            if (status.isFeedback()) {
                return status;
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            // fall through to the validation error below
        }
        throw new DomainException(
                ProblemTypes.VALIDATION_FAILED,
                "label phải là một trong "
                        + Arrays.stream(AnomalyStatus.values())
                                .filter(AnomalyStatus::isFeedback)
                                .map(Enum::name)
                                .toList()
                        + ".");
    }

    private static AnomalyView view(Anomaly a, Map<UUID, String> jarNames) {
        return new AnomalyView(
                a.getId(),
                a.getJarId(),
                jarNames.get(a.getJarId()),
                a.getTransactionId(),
                a.getDetector().name(),
                a.getDirection().name(),
                a.getSeverity().name(),
                a.getScore(),
                a.getThreshold(),
                a.amount(),
                a.getStatus().name(),
                a.getDetectedAt());
    }
}
