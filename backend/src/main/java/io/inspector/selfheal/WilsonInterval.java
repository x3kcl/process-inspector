package io.inspector.selfheal;

/**
 * The 95% Wilson score interval (z = 1.96), used to bound the per-class self-heal rate
 * (RETRYING-RISK-LANE.md §3.2) instead of a bare point estimate. Known caveat, stated on
 * purpose in the design: spells of one class are not independent Bernoulli trials (one
 * underlying outage can produce correlated spells), so this is an honest HEURISTIC bound, not
 * an exact one — the lanes read it through a hysteresis band rather than acting on it directly.
 */
public final class WilsonInterval {

    private static final double Z = 1.96;
    private static final double Z2 = Z * Z;

    private WilsonInterval() {}

    public record Bounds(double low, double high) {}

    public static Bounds score(long successes, long n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }
        double p = (double) successes / n;
        double denom = 1 + Z2 / n;
        double center = (p + Z2 / (2.0 * n)) / denom;
        double margin = (Z * Math.sqrt(p * (1 - p) / n + Z2 / (4.0 * n * n))) / denom;
        double low = Math.max(0.0, center - margin);
        double high = Math.min(1.0, center + margin);
        return new Bounds(low, high);
    }
}
