package io.github.thphuc06.jars.insights.internal.detection;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * What the detector remembers about one jar between transactions: a running mean and variance of its normal
 * spending (Welford's method, so no history needs to be re-read), the two CUSUM accumulators, and a short window
 * of recent amounts for the median-based outlier test.
 *
 * <p>Plain mutable data on purpose: {@link SpendingDetector} is pure maths over this object, and the JPA entity in
 * the persistence layer copies it in and out.
 */
public final class DetectorState {

    private int count;
    private double mean;
    private double m2;
    private double cusumPos;
    private double cusumNeg;
    private final Deque<Double> recent = new ArrayDeque<>();

    public DetectorState() {}

    public DetectorState(
            int count, double mean, double m2, double cusumPos, double cusumNeg, Collection<Double> recentAmounts) {
        this.count = count;
        this.mean = mean;
        this.m2 = m2;
        this.cusumPos = cusumPos;
        this.cusumNeg = cusumNeg;
        this.recent.addAll(recentAmounts);
    }

    /** Welford's online update of the mean and the sum of squared deviations. */
    void learn(double amount) {
        count++;
        double delta = amount - mean;
        mean += delta / count;
        m2 += delta * (amount - mean);
    }

    void remember(double amount, int windowSize) {
        recent.addLast(amount);
        while (recent.size() > windowSize) {
            recent.removeFirst();
        }
    }

    /** Sample standard deviation of the learned amounts; 0 until two amounts have been seen. */
    double sigma() {
        return count < 2 ? 0.0 : Math.sqrt(m2 / (count - 1));
    }

    void setCusum(double pos, double neg) {
        this.cusumPos = pos;
        this.cusumNeg = neg;
    }

    public int count() {
        return count;
    }

    public double mean() {
        return mean;
    }

    public double m2() {
        return m2;
    }

    public double cusumPos() {
        return cusumPos;
    }

    public double cusumNeg() {
        return cusumNeg;
    }

    public List<Double> recent() {
        return List.copyOf(recent);
    }
}
