package io.github.thphuc06.jars.insights.internal.detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether a new expense in a jar is unusual. Two complementary tests, run on every expense:
 *
 * <ol>
 *   <li><b>MAD outlier (a single large expense).</b> With the median {@code m} of the recent amounts and their median
 *       absolute deviation {@code d}, the modified z-score is {@code 0.6745 (x - m) / d} (Iglewicz and Hoaglin).
 *       Above 3.5 the expense is a spike. Median and MAD are not distorted by earlier outliers, unlike mean and
 *       standard deviation. A spike is <em>not</em> fed to the baseline or to CUSUM, otherwise one big purchase would
 *       poison them (the same reason the original hackathon code bypassed CUSUM for z &gt; 4).
 *   <li><b>CUSUM (a sustained shift).</b> {@code z = (x - mean) / sigma} against the learned baseline, then
 *       {@code S+ = max(0, S+ + z - k)} and {@code S- = min(0, S- + z + k)}. When {@code S+ > h} spending has drifted
 *       up for a while (page 1954); when {@code S- < -h} it has drifted down. The accumulators reset after an alarm.
 * </ol>
 *
 * <p>The first {@code minSamples} expenses of a jar only train the baseline. Sigma and the MAD are floored at a share
 * of the mean/median so a jar whose expenses were all identical does not flag a 1 VND difference as infinitely
 * unusual. The maths uses {@code double}: scores are dimensionless, amounts stay {@code Money}/{@code BigDecimal}
 * everywhere else.
 */
public final class SpendingDetector {

    public enum Detector {
        CUSUM,
        MAD
    }

    public enum Direction {
        UP,
        DOWN
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }

    /** @param score the modified z-score (MAD) or the accumulator value that crossed the limit (CUSUM) */
    public record Finding(Detector detector, Direction direction, Severity severity, double score, double threshold) {}

    /**
     * @param k CUSUM slack, in sigmas (0.5 detects shifts of about one sigma)
     * @param h CUSUM decision limit, in sigmas
     * @param madFloorShare the MAD is at least this share of the median
     * @param sigmaFloorShare sigma is at least this share of the mean
     */
    public record Config(
            int minSamples,
            int windowSize,
            int madMinWindow,
            double madThreshold,
            double madFloorShare,
            double sigmaFloorShare,
            double k,
            double h) {}

    public static final Config DEFAULT = new Config(5, 30, 8, 3.5, 0.05, 0.10, 0.5, 5.0);

    /** Consistency constant that makes the MAD comparable to a standard deviation for normal data. */
    private static final double MAD_SCALE = 0.6745;

    private final Config config;

    public SpendingDetector() {
        this(DEFAULT);
    }

    public SpendingDetector(Config config) {
        this.config = config;
    }

    /** Looks at one expense, updates {@code state} accordingly and returns the anomaly it revealed, if any. */
    public Optional<Finding> observe(DetectorState state, double amount) {
        Optional<Finding> finding = Optional.empty();
        boolean spike = false;

        if (state.count() >= config.minSamples()) {
            finding = checkSpike(state, amount);
            spike = finding.isPresent();
            if (!spike) {
                finding = checkDrift(state, amount);
            }
        }

        if (!spike) {
            state.learn(amount);
        }
        // spikes still enter the median window: if a new, higher level of spending persists, median and MAD adapt
        // and the alerts stop, while the mean/sigma baseline and CUSUM stay protected from one-off purchases
        state.remember(amount, config.windowSize());
        return finding;
    }

    private Optional<Finding> checkSpike(DetectorState state, double amount) {
        List<Double> window = state.recent();
        if (window.size() < config.madMinWindow()) {
            return Optional.empty();
        }
        double median = median(window);
        List<Double> deviations = new ArrayList<>(window.size());
        for (double value : window) {
            deviations.add(Math.abs(value - median));
        }
        double mad = Math.max(median(deviations), config.madFloorShare() * median);
        if (mad <= 0) {
            return Optional.empty();
        }
        double modifiedZ = MAD_SCALE * (amount - median) / mad;
        if (modifiedZ <= config.madThreshold()) {
            return Optional.empty();
        }
        Severity severity = modifiedZ > 2 * config.madThreshold() ? Severity.HIGH : Severity.MEDIUM;
        return Optional.of(new Finding(Detector.MAD, Direction.UP, severity, modifiedZ, config.madThreshold()));
    }

    private Optional<Finding> checkDrift(DetectorState state, double amount) {
        double sigma = Math.max(state.sigma(), config.sigmaFloorShare() * state.mean());
        if (sigma <= 0) {
            return Optional.empty();
        }
        double z = (amount - state.mean()) / sigma;
        double pos = Math.max(0.0, state.cusumPos() + z - config.k());
        double neg = Math.min(0.0, state.cusumNeg() + z + config.k());

        if (pos > config.h()) {
            state.setCusum(0.0, 0.0);
            Severity severity = pos > 1.5 * config.h() ? Severity.HIGH : Severity.MEDIUM;
            return Optional.of(new Finding(Detector.CUSUM, Direction.UP, severity, pos, config.h()));
        }
        if (neg < -config.h()) {
            state.setCusum(0.0, 0.0);
            return Optional.of(new Finding(Detector.CUSUM, Direction.DOWN, Severity.LOW, -neg, config.h()));
        }
        state.setCusum(pos, neg);
        return Optional.empty();
    }

    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
